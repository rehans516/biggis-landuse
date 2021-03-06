package biggis.landuse.spark.examples

import com.typesafe.scalalogging.LazyLogging
import geotrellis.raster.DoubleArrayTile
import geotrellis.raster.MultibandTile
import geotrellis.raster.Tile
import geotrellis.spark.io.hadoop.HadoopAttributeStore
import geotrellis.spark.io.hadoop.HadoopLayerDeleter
import geotrellis.spark.io.hadoop.HadoopLayerReader
import geotrellis.spark.io.hadoop.HadoopLayerWriter
import geotrellis.spark.io.index.ZCurveKeyIndexMethod
import geotrellis.spark.io.index.ZCurveKeyIndexMethod.spatialKeyIndexMethod
import geotrellis.spark.io.SpatialKeyFormat
import geotrellis.spark.io.spatialKeyAvroFormat
import geotrellis.spark.io.tileLayerMetadataFormat
import geotrellis.spark.io.tileUnionCodec
import geotrellis.spark.LayerId
import geotrellis.spark.Metadata
import geotrellis.spark.SpatialKey
import geotrellis.spark.TileLayerMetadata
import org.apache.hadoop.fs.Path
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.rdd.RDD._
import org.apache.spark.SparkContext
import org.apache.spark.SparkException

object TilePixelingExample extends LazyLogging {

  def main(args: Array[String]): Unit = {
    try {
      val Array(layerNameIn, layerNameOut, catalogPath) = args
      implicit val sc = Utils.initSparkAutoContext
      TilePixelingExample(layerNameIn, layerNameOut)(catalogPath, sc)
      sc.stop()
    } catch {
      case _: MatchError => println("Run as: layerNameIn layerNameOut /path/to/catalog")
      case e: SparkException => logger error e.getMessage + ". Try to set JVM parmaeter: -Dspark.master=local[*]"
    }
  }

  def apply(layerNameIn: String, layerNameOut: String)(implicit catalogPath: String, sc: SparkContext): Unit = {
    logger info s"Running pixeling of layer '$layerNameIn' in catalog '$catalogPath'"

    // Create the attributes store that will tell us information about our catalog.
    val catalogPathHdfs = new Path(catalogPath)
    val attributeStore = HadoopAttributeStore(catalogPathHdfs)
    val layerReader = HadoopLayerReader(attributeStore)

    val zoomsOfLayer = attributeStore.layerIds filter (_.name == layerNameIn)
    if (zoomsOfLayer.isEmpty) {
      logger info s"Layer '$layerNameIn' not found in the catalog '$catalogPath'"
      return
    }

    val srcLayerId = zoomsOfLayer.sortBy(_.zoom).last
    logger debug s"The following layerId will be used: $srcLayerId"

    //val queryResult: RDD[(SpatialKey, Tile)] with Metadata[TileLayerMetadata[SpatialKey]] = layerReader
    //  .read[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](srcLayerId)

    //For image layers we need multiband
    val queryResult: RDD[(SpatialKey, MultibandTile)] with Metadata[TileLayerMetadata[SpatialKey]] = layerReader
      .read[SpatialKey, MultibandTile, TileLayerMetadata[SpatialKey]](srcLayerId)

    // MultibandTile with Label => Pixel Samples with Label
    val samples: RDD[(SpatialKey, (Int, Int, LabeledPoint))] with Metadata[TileLayerMetadata[SpatialKey]] =
      queryResult.withContext { rdd =>
        rdd.flatMapValues(mbtile =>
          UtilsML.MultibandTile2LabeledPixelSamples(mbtile, classBandNo = 0)
        )
      }

    // ToDo: Spark Streaming write to Kafka queue
    // see: https://spark.apache.org/docs/1.6.2/streaming-kafka-integration.html
    /*  // e.g.
    import org.apache.spark.streaming.kafka._
    val kafkaStream = KafkaUtils.createStream(streamingContext,
      [ZK quorum], [consumer group id], [per-topic number of Kafka partitions to consume])
    */

    /*  // Use UtilsKafka & UtilsSVM to create Kafka Stream from samples
    val (brokers, topic) = ("localhost:9092","neu")
    UtilsKafka.initKafka(topic)(brokers)
    val messages : Array[String] = UtilsSVM.toKafkaString(samples, UtilsSVM.Delimiter(";")).toLocalIterator.toArray
    val nmsg : Int = messages.length
    logger debug s"messages $nmsg"
    for ( imsg <- 0 until nmsg ){
      val str = messages(imsg)
      UtilsKafka.send(str)
    }
    */

    // ToDo: Spark Streaming read from Kafka queue

    // Label (ClassId) of Pixel Samples => Tile
    val outTiles: RDD[(SpatialKey, Tile)] with Metadata[TileLayerMetadata[SpatialKey]] =
      samples.withContext { rdd =>
        rdd.groupByKey().map { case (spatialKey, listOfPixels) =>
          val arr = Array.ofDim[Double](256 * 256)
          listOfPixels.foreach { case (x, y, lp) =>
            arr(x + y * 256) = lp.label
          }

          (spatialKey, DoubleArrayTile(arr, 256, 256))
        }
      }

    // Create the writer that we will use to store the tiles in the local catalog.
    val writer = HadoopLayerWriter(catalogPathHdfs, attributeStore)
    val layerIdOut = LayerId(layerNameOut, srcLayerId.zoom )// "TODO_outlayer" TODO:srcLayerId.zoom

    // If the layer exists already, delete it out before writing
    if (attributeStore.layerExists(layerIdOut)) {
      logger debug s"Layer $layerIdOut already exists, deleting ..."
      HadoopLayerDeleter(attributeStore).delete(layerIdOut)
    }

    logger debug "Writing reprojected tiles using space filling curve"
    writer.write(layerIdOut, outTiles, ZCurveKeyIndexMethod)

    //sc.stop()  //moved to main
    logger info "done."
  }
}
