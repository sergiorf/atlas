package atlas

import org.scalatest.funsuite.AnyFunSuite

class MainTest extends AnyFunSuite {
  test("parses the silver normalization command") {
    assert(
      Main.parseArgs(List("normalize-receita-estabelecimentos")) ===
        Main.Cli("normalize-receita-estabelecimentos")
    )
    assert(
      Main.parseArgs(List("normalize-receita-estabelecimentos", "--config", "custom.conf")) ===
        Main.Cli("normalize-receita-estabelecimentos", "custom.conf")
    )
  }
}
