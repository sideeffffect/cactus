package com.avast.cactus.v3.test

import java.time.{Duration, Instant}

import cats.data.NonEmptyList
import com.avast.cactus._
import com.avast.cactus.v3.TestMessageV3._
import com.avast.cactus.v3.ValueOneOf.NumberValue
import com.avast.cactus.v3._
import com.google.protobuf.{
  Any,
  BoolValue,
  ByteString,
  BytesValue,
  Empty,
  FloatValue,
  Int32Value,
  Int64Value,
  ListValue,
  Struct,
  Value,
  Duration => GpbDuration,
  Timestamp => GpbTimestamp
}
import org.scalatest.FunSuite

import scala.jdk.CollectionConverters._

class CactusMacrosTestV3 extends FunSuite {

  test("GPB to case class") {
    val text = "textěščřžýáíé"

    val gpbInternal = Data2
      .newBuilder()
      .setFieldDouble(0.9)
      .setFieldBlob(ByteString.copyFromUtf8(text))
      .build()

    val map = Map("first" -> "1", "second" -> "2")
    val map2 = Map("first" -> 1, "second" -> 2)

    val dataRepeated = Seq(gpbInternal, gpbInternal, gpbInternal)

    val gpb = TestMessageV3.Data
      .newBuilder()
      .setFieldString("ahoj")
      .setFieldIntName(9)
      //.setFieldOption(13) -> will have 0 value
      .setFieldBlob(ByteString.EMPTY)
      .setFieldGpb(gpbInternal)
      .setFieldGpb2(gpbInternal)
      .setFieldGpb3(Data5.newBuilder().addAllFieldGpb(dataRepeated.asJava).build())
      .setFieldGpbOption(gpbInternal)
      .addAllFieldGpbRepeated(dataRepeated.asJava)
      .addFieldGpb2RepeatedRecurse(Data3.newBuilder().addAllFieldGpb(dataRepeated.asJava).setFooInt(9).build())
      .addAllFieldStrings(Seq("a", "b").asJava)
      .addAllFieldStringsName(Seq("a").asJava)
      .addAllFieldOptionIntegers(Seq(3, 6).map(int2Integer).asJava)
      .addAllFieldIntegers2(Seq(1, 2).map(int2Integer).asJava)
      .addAllFieldMap(map.map { case (key, value) => TestMessageV3.MapMessage.newBuilder().setKey(key).setValue(value).build() }.asJava)
      .addAllFieldMap2(
        map.map { case (key, value) => TestMessageV3.MapMessage.newBuilder().setKey(key).setValue(value.toString).build() }.asJava)
      .build()

    val caseClassB = CaseClassB(0.9, text)

    val caseClassD = Seq(CaseClassD(Seq(caseClassB, caseClassB, caseClassB), OneOfNamed3.FooInt(9)))
    val caseClassF = CaseClassF(Seq(caseClassB, caseClassB, caseClassB), None)

    val expected = CaseClassA(
      fieldString = "ahoj",
      fieldInt = 9,
      fieldOption = Some(0),
      fieldBlob = ByteString.EMPTY,
      fieldStrings2 = List("a"),
      fieldGpb = caseClassB,
      fieldGpb2 = caseClassB,
      fieldGpb3 = caseClassF,
      fieldGpbOption = Some(caseClassB),
      fieldGpbOptionEmpty = None,
      fieldGpbRepeated = Seq(caseClassB, caseClassB, caseClassB),
      fieldGpb2RepeatedRecurse = caseClassD,
      fieldStrings = List("a", "b"),
      fieldOptionIntegers = Vector(3, 6),
      fieldOptionIntegersEmpty = List(),
      fieldIntegersString = "1, 2",
      fieldMap = map,
      fieldMapDiffType = map2
    )

    assertResult(Right(expected))(gpb.asCaseClass[CaseClassA])
  }

  test("GPB to case class multiple failures") {
    val gpbInternal = Data2
      .newBuilder()
      .setFieldDouble(0.9)
      .setFieldBlob(ByteString.copyFromUtf8("text"))
      .build()

    // fields commented out are REQUIRED
    val gpb = TestMessageV3.Data
      .newBuilder()
      .setFieldOption(13)
      .setFieldBlob(ByteString.EMPTY)
      //      .setFieldGpb(gpbInternal)
      .setFieldGpbOption(gpbInternal)
      .addAllFieldStringsName(Seq("a").asJava)
      .addAllFieldOptionIntegers(Seq(3, 6).map(int2Integer).asJava)
      .addFieldGpb2RepeatedRecurse(Data3.newBuilder().build())
      .setFieldGpb3(Data5.newBuilder().build())
      .build()

    val expected = List("gpb.fieldGpb", "gpb.fieldGpb2").map(MissingFieldFailure).sortBy(_.toString) :+ OneOfValueNotSetFailure(
      "gpb.fieldGpb2RepeatedRecurse.NamedOneOf2")

    gpb.asCaseClass[CaseClassA] match {
      case Left(e) =>
        assertResult(expected)(e.toList.sortBy(_.toString))

      case Right(_) => fail("Should fail")
    }
  }

  test("Case class to GPB") {
    val map = Map("first" -> "1", "second" -> "2")
    val map2 = Map("first" -> 1, "second" -> 2)

    val caseClassB = CaseClassB(0.9, "text")

    val caseClassD = Seq(CaseClassD(Seq(caseClassB, caseClassB, caseClassB), OneOfNamed3.FooInt(9)))
    val caseClassF = CaseClassF(Seq(caseClassB, caseClassB, caseClassB), None)

    val caseClass = CaseClassA(
      fieldString = "ahoj",
      fieldInt = 9,
      fieldOption = Some(13),
      fieldBlob = ByteString.EMPTY,
      fieldStrings2 = List("a"),
      fieldGpb = caseClassB,
      fieldGpb2 = caseClassB,
      fieldGpb3 = caseClassF,
      fieldGpbOption = Some(caseClassB),
      fieldGpbOptionEmpty = None,
      fieldGpbRepeated = Seq(caseClassB, caseClassB, caseClassB),
      fieldGpb2RepeatedRecurse = caseClassD,
      fieldStrings = List("a", "b"),
      fieldOptionIntegers = Vector(3, 6),
      fieldOptionIntegersEmpty = List(),
      fieldIntegersString = "1, 2",
      fieldMap = map,
      fieldMapDiffType = map2
    )

    val gpbInternal = Data2
      .newBuilder()
      .setFieldDouble(0.9)
      .setFieldBlob(ByteString.copyFromUtf8("text"))
      .build()

    val dataRepeated = Seq(gpbInternal, gpbInternal, gpbInternal)

    val expectedGpb =
      TestMessageV3.Data
        .newBuilder()
        .setFieldString("ahoj")
        .setFieldIntName(9)
        .setFieldOption(13)
        .setFieldBlob(ByteString.EMPTY)
        .setFieldGpb(gpbInternal)
        .setFieldGpb2(gpbInternal)
        .setFieldGpb3(Data5.newBuilder().addAllFieldGpb(dataRepeated.asJava).build())
        .setFieldGpbOption(gpbInternal)
        .addAllFieldGpbRepeated(dataRepeated.asJava)
        .addFieldGpb2RepeatedRecurse(Data3.newBuilder().addAllFieldGpb(dataRepeated.asJava).setFooInt(9).build())
        .addAllFieldStrings(Seq("a", "b").asJava)
        .addAllFieldStringsName(Seq("a").asJava)
        .addAllFieldOptionIntegers(Seq(3, 6).map(int2Integer).asJava)
        .addAllFieldMap(map.map { case (key, value) => TestMessageV3.MapMessage.newBuilder().setKey(key).setValue(value).build() }.asJava)
        .addAllFieldMap2(map2.map {
          case (key, value) => TestMessageV3.MapMessage.newBuilder().setKey(key).setValue(value.toString).build()
        }.asJava)
        .addAllFieldIntegers2(Seq(1, 2).map(int2Integer).asJava)
        .build()

    caseClass.asGpb[Data] match {
      case Right(e) if e == expectedGpb => // ok
    }
  }

  test("one-of with case object") {
    val gpb = Data3.newBuilder().setFooEmpty(Empty.getDefaultInstance).build()

    val caseClassD = CaseClassD(Seq(), OneOfNamed3.FooEmpty)

    val Right(cc) = gpb.asCaseClass[CaseClassD]

    assertResult(caseClassD)(cc)

    val Right(convGpb) = cc.asGpb[Data3]

    assertResult(gpb)(convGpb)
  }

  test("convert case class to GPB and back") {
    val map = Map("first" -> "1", "second" -> "2")

    val original = CaseClassC(
      fieldString = StringWrapperClass("ahoj"),
      fieldInt = 9,
      fieldOption = Some(13),
      fieldBlob = ByteString.EMPTY,
      fieldStrings2 = Vector("a"),
      fieldGpb = CaseClassB(0.9, "text"),
      fieldGpbOption = Some(CaseClassB(0.9, "text")),
      fieldGpbOptionEmpty = None,
      fieldStrings = Array("a", "b"),
      fieldOptionIntegers = Vector(3, 6),
      fieldOptionIntegersEmpty = List(),
      fieldMap = map
    )

    val Right(converted) = original.asGpb[Data]

    assertResult(Right(original))(converted.asCaseClass[CaseClassC])
  }

  test("convert case class with ignored field to GPB and back") {
    val original = CaseClassE(fieldString = "ahoj", fieldOption = Some("ahoj2"))

    val Right(converted) = original.asGpb[Data4]

    assertResult(Right(original))(converted.asCaseClass[CaseClassE])
  }

  test("gpb3 map to GPB and back") {
    val original = CaseClassG(fieldString = "ahoj",
                              fieldOption = Some("ahoj2"),
                              fieldMap = Map("one" -> 1, "two" -> 2),
                              fieldMap2 = Map("one" -> CaseClassMapInnerMessage("str", 42)))

    val Right(converted) = original.asGpb[Data4]

    assertResult(Right(original))(converted.asCaseClass[CaseClassG])
  }

  test("extensions from GPB and back") {
    val gpb = ExtensionsMessage
      .newBuilder()
      .setBoolValue(BoolValue.newBuilder().setValue(true))
      .setInt32Value(Int32Value.newBuilder().setValue(123))
      .setInt64Value(Int64Value.newBuilder().setValue(456))
      .setFloatValue(FloatValue.newBuilder().setValue(123.456f))
      .setBytesValue(BytesValue.newBuilder().setValue(ByteString.copyFromUtf8("+ěščřžýáíé")))
      .setDuration(GpbDuration.newBuilder().setSeconds(123).setNanos(456))
      .setTimestamp(GpbTimestamp.newBuilder().setSeconds(123).setNanos(456))
      .setListValue(ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(456.789)))
      .setListValue2(ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(456.789)))
      .setListValue3(ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(456.789)))
      .setStruct(Struct.newBuilder().putFields("mapKey", Value.newBuilder().setNumberValue(42).build()))
      .build()

    val expected = CaseClassExtensions(
      boolValue = BoolValue.newBuilder().setValue(true).build(),
      int32Value = Int32Value.newBuilder().setValue(123).build(),
      longValue = Int64Value.newBuilder().setValue(456).build(),
      floatValue = Some(FloatValue.newBuilder().setValue(123.456f).build()),
      doubleValue = None,
      stringValue = None,
      bytesValue = BytesValue.newBuilder().setValue(ByteString.copyFromUtf8("+ěščřžýáíé")).build(),
      duration = GpbDuration.newBuilder().setSeconds(123).setNanos(456).build(),
      timestamp = GpbTimestamp.newBuilder().setSeconds(123).setNanos(456).build(),
      listValue = ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(456.789)).build(),
      listValue2 = Seq(NumberValue(456.789)),
      listValue3 = Some(Seq(NumberValue(456.789))),
      listValue4 = None,
      struct = Map("mapKey" -> NumberValue(42))
    )

    val Right(converted) = gpb.asCaseClass[CaseClassExtensions]

    assertResult(expected)(converted)

    assertResult(Right(gpb))(converted.asGpb[ExtensionsMessage])
  }

  test("extensions from GPB and back - scala types") {
    val gpb = ExtensionsMessage
      .newBuilder()
      .setBoolValue(BoolValue.newBuilder().setValue(true))
      .setInt32Value(Int32Value.newBuilder().setValue(123))
      .setInt64Value(Int64Value.newBuilder().setValue(456))
      .setFloatValue(FloatValue.newBuilder().setValue(123.456f))
      .setBytesValue(BytesValue.newBuilder().setValue(ByteString.copyFromUtf8("+ěščřžýáíé")))
      .setDuration(GpbDuration.newBuilder().setSeconds(123).setNanos(456))
      .setTimestamp(GpbTimestamp.newBuilder().setSeconds(123).setNanos(456))
      .setListValue(ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(456.789)))
      .build()

    val expected = CaseClassExtensionsScala(
      boolValue = true,
      int32Value = 123,
      longValue = 456,
      floatValue = Some(123.456f),
      doubleValue = None,
      stringValue = None,
      bytesValue = ByteString.copyFromUtf8("+ěščřžýáíé"),
      duration = Duration.ofSeconds(123, 456),
      timestamp = Instant.ofEpochSecond(123, 456),
      listValue = Seq(NumberValue(456.789))
    )

    val Right(converted) = gpb.asCaseClass[CaseClassExtensionsScala]

    assertResult(expected)(converted)

    assertResult(Right(gpb))(converted.asGpb[ExtensionsMessage])
  }

  test("message with enum") {
    val gpb = MessageWithRawEnum
      .newBuilder()
      .setFieldString("ahoj")
      .setFieldEnum(TestEnum.FIRST)
      .setFieldEnumOption(TestEnum.SECOND)
      .putAllFieldMap(Map("first" -> TestEnum.FIRST, "second" -> TestEnum.SECOND).asJava)
      .build()

    val ccl = CaseClassWithRawEnum(
      fieldString = "ahoj",
      fieldEnum = TestEnum.FIRST,
      fieldEnumOption = Some(TestEnum.SECOND),
      fieldMap = Map("first" -> TestEnum.FIRST, "second" -> TestEnum.SECOND)
    )

    assertResult(Right(ccl))(gpb.asCaseClass[CaseClassWithRawEnum])
    assertResult(Right(gpb))(ccl.asGpb[MessageWithRawEnum])

    val gpb2 = MessageWithEnum.newBuilder().setTheEnumField(MessageWithEnum.TheEnum.TWO).build()

    assertResult(Right(CaseClassWithEnum(Some(TheEnum.Two))))(gpb2.asCaseClass[CaseClassWithEnum])
    assertResult(Right(gpb2))(CaseClassWithEnum(Some(TheEnum.Two)).asGpb[MessageWithEnum])
  }

  test("message with enum - own enum converter") {
    val gpb = MessageWithRawEnum
      .newBuilder()
      .setFieldString("ahoj")
      .setFieldEnum(TestEnum.FIRST)
      .build()

    val ccl = CaseClassWithRawEnum(
      fieldString = "ahoj",
      fieldEnum = TestEnum.FIRST,
      fieldEnumOption = None,
      fieldMap = Map.empty
    )

    assertResult(Right(ccl.copy(fieldEnumOption = Some(TestEnum.UNKNOWN))))(gpb.asCaseClass[CaseClassWithRawEnum])
    assertResult(Right(gpb))(ccl.asGpb[MessageWithRawEnum])

    implicit val c: Converter[MessageWithEnum.TheEnum, TheEnum] = Converter(_ => TheEnum.Two)
    implicit val c2: Converter[TheEnum, MessageWithEnum.TheEnum] = Converter(_ => MessageWithEnum.TheEnum.ONE)

    val gpb2 = MessageWithEnum.newBuilder().setTheEnumField(MessageWithEnum.TheEnum.ONE).build()
    val ccl2 = CaseClassWithEnum(Some(TheEnum.Two))

    assertResult(Right(ccl2))(gpb2.asCaseClass[CaseClassWithEnum])
    assertResult(Right(gpb2))(ccl2.asGpb[MessageWithEnum])
  }

  test("it's possible to derive converter from enum to sealed trait and back") {
    checkDoesNotCompile {
      """
        |Converter.deriveConverter[TestEnum, TheEnum]
      """.stripMargin
    }

    checkDoesNotCompile {
      """
        |Converter.deriveConverter[TheEnum, TestEnum]
      """.stripMargin
    }

    checkCompiles {
      """
        |Converter.deriveConverter[MessageWithEnum.TheEnum, TheEnum]
      """.stripMargin
    }

    checkCompiles {
      """
        |Converter.deriveConverter[TheEnum, MessageWithEnum.TheEnum]
      """.stripMargin
    }
  }

  test("is possible to derive Option[A] -> OptionalResponse and back converters through AnyValue") {
    implicit val convToCc: Converter[OptionalMessage, Option[InnerClass]] = {
      implicitly[Converter[Option[AnyValue], Option[MessageInsideAnyField]]]
        .andThen[Option[InnerClass]]
        .contraMap[OptionalMessageClass](_.elem)
        .compose[OptionalMessage]
    }

    val convToGpb: Converter[Option[InnerClass], OptionalMessage] = {
      implicitly[Converter[Option[InnerClass], Option[MessageInsideAnyField]]]
        .andThen[Option[AnyValue]]
        .map(OptionalMessageClass)
        .andThen[OptionalMessage]
    }

    val convThereAndBack: Converter[Option[InnerClass], Option[InnerClass]] = convToGpb.andThen(convToCc)

    val innerMessage = MessageInsideAnyField.newBuilder().setFieldInt(42).setFieldString("ahoj").build()

    {
      val gpb = OptionalMessage.newBuilder().build()

      assertResult(Right(gpb))(convToGpb.apply("")(None))
      assertResult(Right(None))(convToCc.apply("")(gpb))
      assertResult(Right(None))(convThereAndBack.apply("")(None))
    }

    {
      val cc = Some(InnerClass(42, "ahoj"))
      val gpb = OptionalMessage.newBuilder().setElem(Any.pack(innerMessage)).build()

      assertResult(Right(gpb))(convToGpb.apply("")(cc))
      assertResult(Right(cc))(convToCc.apply("")(gpb))
      assertResult(Right(cc))(convThereAndBack.apply("")(cc))
    }
  }

  test("able to convert generic case class to gpb and back") {
    val gpb = MessageWithStringAndInt
      .newBuilder()
      .setFieldString("123456")
      .setFieldInt(42)
      .build()

    val caseClassString = GenericCaseClass[String]("123456", 42)
    val caseClassInt = GenericCaseClass[Int](123456, 42)

    assertResult(Right(caseClassString))(gpb.asCaseClass[GenericCaseClass[String]])
    assertResult(Right(caseClassInt))(gpb.asCaseClass[GenericCaseClass[Int]])

    assertResult(Right(gpb))(caseClassString.asGpb[MessageWithStringAndInt])
    assertResult(Right(gpb))(caseClassInt.asGpb[MessageWithStringAndInt])
  }

  test("fails when having null fields") {
    val original = CaseClassG(fieldString = "ahoj", fieldOption = Some(null), fieldMap = Map.empty, fieldMap2 = null)

    assertResult(Left {
      NonEmptyList.of(
        InvalidValueFailure("original.fieldOption", "Some(null)"),
        InvalidValueFailure("original.fieldMap2", "null"),
      )
    })(original.asGpb[Data4])
  }

  test("map of options conversion") {
    // missing converters:
    checkDoesNotCompile {
      """
        |val original = CaseClassWithMap(values = Map("now" -> Some(Instant.now())))
        |original.asGpb[MessageWithMap1]
        |""".stripMargin
    }

    {
      implicit val c: Converter[Option[Instant], GpbTimestamp] = instant2gpbTimestamp.contraMap(_.get)
      // Converter[GpbTimestamp, Option[Instant]] is derived automatically

      val original = CaseClassWithMap(values = Map("now" -> Some(Instant.ofEpochSecond(42))))
      val Right(converted) = original.asGpb[MessageWithMap1]

      assertResult(Right(original))(converted.asCaseClass[CaseClassWithMap])
    }

    // missing converters:
    checkDoesNotCompile {
      """
        |val original = CaseClassWithMapEnums(values = Map("now" -> Some(TheEnum.Two)))
        |original.asGpb[MessageWithMap2]
        |""".stripMargin
    }

    {
      implicit val c: Converter[Option[com.avast.cactus.v3.test.TheEnum], com.avast.cactus.v3.TestMessageV3.MessageWithEnum.TheEnum] =
        Converter(_ => com.avast.cactus.v3.TestMessageV3.MessageWithEnum.TheEnum.TWO)

      // Converter[MessageWithEnum.TheEnum, Option[TheEnum]] is derived automatically

      val original = CaseClassWithMapEnums(values = Map("now" -> Some(TheEnum.Two)))
      val Right(converted) = original.asGpb[MessageWithMap2]

      assertResult(Right(original))(converted.asCaseClass[CaseClassWithMapEnums])
    }
  }
}
