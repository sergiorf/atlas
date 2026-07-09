package atlas.release

import org.scalatest.funsuite.AnyFunSuite

class ReleaseIdTest extends AnyFunSuite {
  test("accepts valid release ids") {
    assert(ReleaseId.parse("2026-01").right.get.value === "2026-01")
    assert(ReleaseId.parse("2026-12").right.get.value === "2026-12")
  }

  test("rejects invalid and dangerous release ids") {
    Seq("2026", "2026-1", "2026-13", "../../raw", "/tmp/2026-03", "release=2026-03").foreach { value =>
      assert(ReleaseId.parse(value).isLeft, value)
    }
  }
}
