package biggis.landuse

import com.typesafe.scalalogging.LazyLogging
import geotrellis.raster.{MultibandTile, Tile}
import geotrellis.raster.io.HistogramDoubleFormat
import geotrellis.spark.LayerId
import geotrellis.spark.Metadata
import geotrellis.spark.SpaceTimeKey
import geotrellis.spark.SpatialKey
import geotrellis.spark.TileLayerMetadata
import geotrellis.spark.io.hadoop.HadoopAttributeStore
import geotrellis.spark.io.hadoop.HadoopLayerDeleter
import geotrellis.spark.io.hadoop.HadoopLayerWriter
import geotrellis.spark.io.index.HilbertKeyIndexMethod
import geotrellis.spark.io.index.ZCurveKeyIndexMethod
import geotrellis.spark.io.json.Implicits._
import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.RemoteIterator
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.reflect.runtime.universe._
import scala.language.implicitConversions

package object api extends LazyLogging {

  /**
    * Converts RemoteIterator from Hadoop to Scala Iterator that provides all the familiar functions such as map,
    * filter, foreach, etc.
    *
    * @param underlying The RemoteIterator that needs to be wrapped
    * @tparam T Items inside the iterator
    * @return Standard Scala Iterator
    */
  implicit def convertToScalaIterator[T](underlying: RemoteIterator[T]): Iterator[T] = {
    case class wrapper(underlying: RemoteIterator[T]) extends Iterator[T] {
      override def hasNext : Boolean = underlying.hasNext

      override def next : T = underlying.next
    }
    wrapper(underlying)
  }

  implicit def catalogToStore(catalogPath: String)(implicit sc: SparkContext): HadoopAttributeStore = {
    val hdfsPath = new Path(catalogPath)
    HadoopAttributeStore(hdfsPath)
  }

  def deleteLayerFromCatalog(layerId: LayerId)(implicit catalogPath: String, sc: SparkContext): Unit = {
    deleteLayerFromCatalog(layerId.name)
  }

  /**
    * Unconditionally deletes a raster layer (including all zoom levels) from a geotrellis catalog.
    * Does not complain if the layer does not exist.
    */
  def deleteLayerFromCatalog(layerName: String)(implicit catalogPath: String, sc: SparkContext): Unit = {

    val store = catalogToStore(catalogPath)

    val deleter = HadoopLayerDeleter(store)
    deleter.attributeStore.layerIds filter (_.name == layerName) foreach deleter.delete

    // try to delete the directory if it still exists
    // we do this because geotrellis leaves an empty directory behind
    // we could delete this step once geotrellis implementation is fixed
    val layerPath = store.rootPath.suffix(s"/$layerName")
    store.fs.delete(layerPath, true)
  }

  /**
    * Unconditionally deletes a single zoom level within a raster layer (inside a geotrellis catalog).
    * Does not complain if the zoom level does not exist.
    */
  def deleteZoomLevelFromLayer(layerName: String, zoomLevel: Int)
                              (implicit catalogPath: String, sc: SparkContext): Unit = {
    val deleter = HadoopLayerDeleter(catalogPath)

    deleter.attributeStore.layerIds filter (_.name == layerName) filter (_.zoom == zoomLevel) foreach { layerId =>
      deleter.delete(layerId)
      logger debug s"Deleted $layerId"
    }
  }

  /**
    * @param rdd         The RDD representing a processed layer of tiles
    * @param layerId     layerName and zoom level
    * @param catalogPath Geotrellis catalog
    * @param sc          SparkContext
    */
  def writeRddToLayer[K, T]
  (rdd: RDD[(K, T)] with Metadata[TileLayerMetadata[K]], layerId: LayerId)
  (implicit catalogPath: String, sc: SparkContext, ttagKey: TypeTag[K], ttagTile: TypeTag[T]): Unit = {

    logger debug s"Writing RDD to layer '${layerId.name}' at zoom level ${layerId.zoom} ..."

    val writer = HadoopLayerWriter(new Path(catalogPath))

    if (ttagKey.tpe =:= typeOf[SpatialKey] && ttagTile.tpe =:= typeOf[Tile]) {

      logger debug s"Writing using SpatialKey + ZCurveKeyIndexMethod + Tile ..."
      val rdd2 = rdd.asInstanceOf[RDD[(SpatialKey, Tile)] with Metadata[TileLayerMetadata[SpatialKey]]]
      writer.write(layerId, rdd2, ZCurveKeyIndexMethod)

      logger debug s"Writing histogram of layer '${layerId.name}' to attribute store as 'histogramData' for zoom level 0"
      writer.attributeStore.write(LayerId(layerId.name, 0), "histogramData", rdd2.histogram)

    } else if (ttagKey.tpe =:= typeOf[SpaceTimeKey]&& ttagTile.tpe =:= typeOf[Tile]) {

      logger debug s"Writing using SpaceTimeKey + HilbertKeyIndexMethod + Tile ..."
      val rdd2 = rdd.asInstanceOf[RDD[(SpaceTimeKey, Tile)] with Metadata[TileLayerMetadata[SpaceTimeKey]]]
      writer.write(layerId, rdd2, HilbertKeyIndexMethod(1))

    } else if(ttagKey.tpe =:= typeOf[SpatialKey] && ttagTile.tpe =:= typeOf[MultibandTile]) {

      logger debug s"Writing using SpatialKey + ZCurveKeyIndexMethod + MultibandTile ..."
      val rdd2 = rdd.asInstanceOf[RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]]]
      writer.write(layerId, rdd2, ZCurveKeyIndexMethod)

    } else if(ttagKey.tpe =:= typeOf[SpaceTimeKey] && ttagTile.tpe =:= typeOf[MultibandTile]) {

      logger debug s"Writing using SpaceTimeKey + HilbertKeyIndexMethod + MultibandTile ..."
      val rdd2 = rdd.asInstanceOf[RDD[(SpaceTimeKey, MultibandTile)] with Metadata[TileLayerMetadata[SpaceTimeKey]]]
      writer.write(layerId, rdd2, HilbertKeyIndexMethod(1))

    } else if(!(ttagKey.tpe =:= typeOf[SpatialKey]) && !(ttagKey.tpe =:= typeOf[SpaceTimeKey])
      && !(ttagTile.tpe =:= typeOf[Tile]) && !(ttagTile.tpe =:= typeOf[MultibandTile]) ) {
      throw new RuntimeException("we did not expect any other key type than SpatialKey or SpaceTimeKey and any other tile type than Tile or MultibandTile")
    } else if(!(ttagTile.tpe =:= typeOf[Tile]) && !(ttagTile.tpe =:= typeOf[MultibandTile]) ) {
      throw new RuntimeException("we did not expect any other type than Tile or MultibandTile")
    } else {
      throw new RuntimeException("we did not expect any other type than SpatialKey or SpaceTimeKey")
    }

    logger debug s"Writing done..."
  }
}