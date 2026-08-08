package atlas.release

import atlas.config.AtlasConfig
import java.nio.file.{Files, LinkOption, Paths}
import scala.collection.JavaConverters._

final case class WslReclaimPreflight(
    isWsl: Boolean,
    distribution: Option[String],
    filesystemTotalBytes: Long,
    filesystemFreeBytes: Long,
    trashBytes: Long,
    transactionFiles: Seq[String],
    sparkProcesses: Seq[String],
    ready: Boolean,
    blockingReasons: Seq[String]
)

object WslReclaimPreflightService {
  val ContractVersion = 1

  def inspect(config: AtlasConfig): WslReclaimPreflight = {
    val dataRoot = ReleasePaths(config).dataRoot
    val store = Files.getFileStore(dataRoot)
    val version = try Files.readString(Paths.get("/proc/version")) catch { case _: Throwable => "" }
    val isWsl = version.toLowerCase.contains("microsoft") || sys.env.contains("WSL_DISTRO_NAME")
    val distribution = sys.env.get("WSL_DISTRO_NAME").filter(_.nonEmpty)
    val transactions = children(ReleasePaths(config).atlasRoot.resolve("transactions"))
      .filter(Files.isRegularFile(_, LinkOption.NOFOLLOW_LINKS)).map(_.toString)
    val spark = ProcessHandle.allProcesses().iterator().asScala.flatMap { process =>
      val command = process.info().commandLine()
      val lower = if (command.isPresent) command.get().toLowerCase else ""
      val sparkCommand = lower.contains("spark-submit") || lower.contains("spark-shell") || lower.contains("org.apache.spark.deploy")
      val otherAtlasJob = lower.contains("atlas.main") && !lower.contains("storage reclaim") && process.pid() != ProcessHandle.current().pid()
      if (sparkCommand || otherAtlasJob) Some(command.get()) else None
    }.toSeq.sorted
    val trashBytes = treeBytes(ReleasePaths(config).atlasRoot.resolve("_trash"))
    val reasons = Seq(
      if (!isWsl) Some("Atlas is not running inside WSL 2") else None,
      if (distribution.isEmpty) Some("WSL_DISTRO_NAME is unavailable; pass -Distro explicitly") else None,
      if (transactions.nonEmpty) Some("active Atlas transaction journals exist") else None,
      if (spark.nonEmpty) Some("Spark processes appear to be running") else None,
      if (trashBytes > 0) Some("Atlas trash still occupies Linux filesystem space") else None
    ).flatten
    WslReclaimPreflight(isWsl, distribution, store.getTotalSpace, store.getUsableSpace, trashBytes, transactions, spark, reasons.isEmpty, reasons)
  }

  def render(result: WslReclaimPreflight): String = {
    val distro = result.distribution.getOrElse("<DISTRO>")
    val command = s"powershell.exe -NoProfile -File .\\scripts\\compact-atlas-wsl.ps1 -Distro '$distro'"
    s"""ATLAS WSL RECLAMATION PREFLIGHT
       |WSL detected: ${result.isWsl}
       |Distribution: ${result.distribution.getOrElse("unknown")}
       |Linux filesystem total bytes: ${result.filesystemTotalBytes}
       |Linux filesystem free bytes: ${result.filesystemFreeBytes}
       |Atlas trash bytes: ${result.trashBytes}
       |Ready to stop WSL and compact: ${result.ready}
       |Blocking reasons: ${if (result.blockingReasons.isEmpty) "none" else result.blockingReasons.mkString("; ")}
       |
       |Run from Windows PowerShell after reviewing the script's dry run:
       |$command
       |Add -Force only after the resolved distribution and VHD path are correct.
       |The Windows command shuts down WSL and will terminate this shell.""".stripMargin
  }

  def json(result: WslReclaimPreflight): String = {
    def quote(value: String): String = "\"" + value.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c => c.toString
    } + "\""
    s"""{"contract_version":$ContractVersion,"is_wsl":${result.isWsl},"distribution":${result.distribution.fold("null")(quote)},"filesystem_total_bytes":${result.filesystemTotalBytes},"filesystem_free_bytes":${result.filesystemFreeBytes},"trash_bytes":${result.trashBytes},"transaction_files":[${result.transactionFiles.map(quote).mkString(",")}],"spark_processes":[${result.sparkProcesses.map(quote).mkString(",")}],"ready":${result.ready},"blocking_reasons":[${result.blockingReasons.map(quote).mkString(",")}] }"""
  }

  private def treeBytes(root: java.nio.file.Path): Long =
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) 0L
    else { val stream = Files.walk(root); try stream.iterator().asScala.filter(Files.isRegularFile(_, LinkOption.NOFOLLOW_LINKS)).map(Files.size(_)).sum finally stream.close() }

  private def children(root: java.nio.file.Path): Seq[java.nio.file.Path] =
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Seq.empty
    else { val stream = Files.list(root); try stream.iterator().asScala.toVector finally stream.close() }
}
