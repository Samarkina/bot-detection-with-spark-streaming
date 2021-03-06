import java.sql.Timestamp

import com.datastax.spark.connector.cql.CassandraConnector
import io.circe.Decoder
import org.apache.spark.sql.{Dataset, ForeachWriter, SparkSession}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.{collect_set, sum, window}
import org.apache.spark.sql.streaming.{DataStreamWriter, OutputMode, Trigger}
import io.circe.parser._


class CassandraWriter(val connector: CassandraConnector) extends ForeachWriter[AggregatedMessage] {
  val KEYSPACE = "StreamingDB"
  val TABLE = "BotsStructured"

  def open(partitionId: Long, version: Long): Boolean = {
    // open connection
    true
  }

  def process(record: AggregatedMessage): Unit= {
    // write string to connection
    connector.withSessionDo(session => session.execute(cassandraQuery(record)))
  }

  def close(errorOrNull: Throwable): Unit = {
    // close the connection
  }

  def cassandraQuery(record: AggregatedMessage): String =
    s"""INSERT INTO $KEYSPACE.$TABLE (ip) VALUES('${record.ip}') USING TTL 600"""

}


object StructStream {
  def writeToCassandra(spark: SparkSession, ds: Dataset[AggregatedMessage]): DataStreamWriter[AggregatedMessage] = {
    ds.writeStream
      .foreach(new CassandraWriter(CassandraConnector(spark.sparkContext.getConf)))
  }

  def parseDF(df: Dataset[(String, String)], spark: SparkSession): Dataset[Message] = {
    import spark.implicits._
    df
    .flatMap(str => {
      val msg = decode[Message](str._2)
      if (msg.isRight)
        Some(msg.right.get)
      else
        None
    })
  }

  def aggregateDF(transformedDF: Dataset[TransformedMessage], spark: SparkSession): Dataset[AggregatedMessage] = {
    import org.apache.spark.sql.functions._
    import spark.implicits._
    transformedDF
      .groupBy($"ip", window($"unix_time", "10 minutes", "60 seconds"))
      .agg(
        sum($"clicks").alias("clicks"),
        sum($"views").alias("views"),
        collect_set($"category_id").alias("categories")
      )
      .drop("window")
      .as[AggregatedMessage]
  }

  def main(): Unit = {
    val spark = SparkSession.builder()
      .master("local[*]")
       .config("spark.sql.streaming.checkpointLocation", "/tmp/sparkCheckpoint")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    import spark.implicits._

    val df = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "localhost:9092")
      .option("subscribe", "kafkaConnectStandalone")
      .load()
      .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")
      .as[(String, String)]

    val decodedDF = parseDF(df, spark)

    val transformedDF = decodedDF
      .map(mess => Message.transform(mess))

    val transformedDFTimestamp = transformedDF
        .map(mess => Message.transformedToTimestamp(mess))

    val aggregated = aggregateDF(transformedDFTimestamp, spark)

    val bots = aggregated
        .filter(mess => IsBot.classification(mess.clicks, mess.views, mess.categories))

    val exportedIps = writeToCassandra(spark, bots)
      .trigger(Trigger.ProcessingTime("20 seconds"))

    exportedIps
      .outputMode(OutputMode.Update())
      .start()
      .awaitTermination()

  }
}