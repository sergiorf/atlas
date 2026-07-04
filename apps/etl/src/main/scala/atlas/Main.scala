package atlas

import atlas.common.SparkSessionFactory
import atlas.config.AtlasConfig
import atlas.receita.ReceitaIngestJob
import atlas.receita.SilverEstablishmentJob
import atlas.status.{RunStatusRegistry, StatusTable}
import java.nio.file.Paths

object Main {
  private[atlas] final case class Cli(command: String, configPath: String = "conf/application.conf")
  def main(args: Array[String]): Unit = {
    val cli = parseArgs(args.toList)
    val config = AtlasConfig.load(cli.configPath)
    cli.command match {
      case "status" => printStatus(config.statusDir)
      case "ingest-receita-estabelecimentos" =>
        withSpark(config) { spark =>
          val result = ReceitaIngestJob.run(spark, config)
          println(s"Ingested ${result.rowCount} rows to ${result.outputPath}")
        }
      case "normalize-receita-estabelecimentos" =>
        withSpark(config) { spark =>
          val result = SilverEstablishmentJob.run(spark, config)
          println(s"Normalized ${result.rowCount} rows to ${result.outputPath}")
        }
      case unknown => throw new IllegalArgumentException(s"Unknown command: $unknown")
    }
  }

  private def withSpark(
      config: AtlasConfig
  )(run: org.apache.spark.sql.SparkSession => Unit): Unit = {
    val spark = SparkSessionFactory.create(config.spark)
    try run(spark)
    finally spark.stop()
  }

  private[atlas] def statusOutput(statusDir: String): String = {
    val scan = RunStatusRegistry.scan(Paths.get(statusDir))
    val statusText =
      if (scan.statuses.isEmpty)
        "No ETL status has been recorded yet. A successful ingest command will create status files."
      else StatusTable.render(scan.statuses)
    val errors = scan.errors.map(error => s"Malformed status file ${error.path}: ${error.message}")
    (statusText +: errors).mkString("\n")
  }

  private def printStatus(statusDir: String): Unit = println(statusOutput(statusDir))
  private[atlas] def parseArgs(args: List[String]): Cli = args match {
    case command :: "--config" :: path :: Nil => Cli(command, path)
    case command :: Nil                       => Cli(command)
    case _ =>
      throw new IllegalArgumentException(
        "Usage: atlas.Main <ingest-receita-estabelecimentos|normalize-receita-estabelecimentos|status> [--config PATH]"
      )
  }
}
