package me.ivovk.cedi

import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Resource}
import org.scalatest.flatspec.AnyFlatSpec

//noinspection TypeAnnotation
class AllocatorTest extends AnyFlatSpec {

  trait ctx {
    object TestDependencies {
      def apply(runtime: IORuntime): Resource[IO, TestDependencies] =
        Allocator.create[IO]()
          .map(_.withListener(new LoggingAllocationListener[IO]))
          .map(new TestDependencies(_))

      val shutdownOrderCapturer: Ref[IO, Seq[String]] = Ref.unsafe(Seq.empty)
    }

    class TestDependencies(allocator: Allocator[IO]) {

      import TestDependencies.*

      lazy val testResourceA: String = allocator.allocate {
        Resource.make(IO("resourceA")) { _ =>
          shutdownOrderCapturer.update(_ :+ "A").void
        }
      }

      lazy val testResourceB: String = allocator.allocate {
        Resource.make(IO(s"resourceB, but depends on $testResourceA")) { _ =>
          shutdownOrderCapturer.update(_ :+ "B").void
        }
      }

    }
  }

  "Allocator" should "allocate a resource" in new ctx {
    val testDependencies = TestDependencies(global)
    val testResource     = testDependencies.use { deps =>
      IO.pure(deps.testResourceA)
    }.unsafeRunSync()

    assert(testResource == "resourceA")
  }

  it should "allocate resources in the correct order" in new ctx {
    val testDependencies = TestDependencies(global)
    testDependencies.use { deps =>
      IO.pure(deps.testResourceB)
    }.unsafeRunSync()

    val resourceShutdownOrder = TestDependencies.shutdownOrderCapturer.get.unsafeRunSync()
    assert(resourceShutdownOrder == Seq("B", "A"))
  }

}
