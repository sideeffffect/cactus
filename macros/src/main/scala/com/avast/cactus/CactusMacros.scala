package com.avast.cactus

import org.scalactic.{Every, Or}

import scala.collection.TraversableLike
import scala.collection.generic.CanBuildFrom
import scala.language.experimental.macros
import scala.language.{higherKinds, implicitConversions}
import scala.reflect.macros._

//noinspection TypeAnnotation
object CactusMacros {

  private val Debug = false

  private val OptPattern = "Option\\[(.*)\\]".r

  implicit def CollAToCollB[A, B, T[X] <: TraversableLike[X, T[X]]](coll: T[A])(implicit cbf: CanBuildFrom[T[A], B, T[B]], aToBConverter: Converter[A, B]): T[B] = {
    CollAToCollBGenerator[A, B, T].apply(coll)
  }

  implicit def CollAToCollBGenerator[A, B, T[X] <: TraversableLike[X, T[X]]](implicit cbf: CanBuildFrom[T[A], B, T[B]], aToBConverter: Converter[A, B]): Converter[T[A], T[B]] = Converter {
    _.map(aToBConverter.apply)
  }

  implicit def OrAToOrB[A, B](implicit aToBConverter: Converter[A, B]): Converter[Or[A, Every[CactusFailure]], Or[B, Every[CactusFailure]]] = Converter {
    _.map(aToBConverter.apply)
  }

  implicit def AToB[A, B](a: A)(implicit aToBConverter: Converter[A, B]): B = {
    aToBConverter.apply(a)
  }

  def convertGpbToCaseClass[CaseClass: c.WeakTypeTag](c: whitebox.Context)(gpbCt: c.Tree): c.Expr[CaseClass Or Every[CactusFailure]] = {
    import c.universe._

    // unpack the implicit ClassTag tree
    val gpbSymbol = extractSymbolFromClassTag(c)(gpbCt)

    val variableName = getVariableName(c)

    c.Expr[CaseClass Or Every[CactusFailure]] {
      val caseClassType = weakTypeOf[CaseClass]
      val gpbType = gpbSymbol.typeSignature.asInstanceOf[c.universe.Type]

      val tree =
        q""" {
          import com.avast.cactus.CactusFailure
          import com.avast.cactus.CactusMacros._

          import org.scalactic._
          import org.scalactic.Accumulation._

          import scala.util.Try
          import scala.collection.JavaConverters._

          ${GpbToCaseClass.createConverter(c)(caseClassType, gpbType, variableName)}
         }
        """

      if (Debug) println(tree)

      tree
    }
  }

  def convertCaseClassToGpb[Gpb: c.WeakTypeTag](c: whitebox.Context)(caseClassCt: c.Tree): c.Expr[Gpb Or Every[CactusFailure]] = {
    import c.universe._

    // unpack the implicit ClassTag tree
    val caseClassSymbol = extractSymbolFromClassTag(c)(caseClassCt)

    val variableName = getVariableName(c)

    c.Expr[Gpb Or Every[CactusFailure]] {
      val caseClassType = caseClassSymbol.typeSignature.asInstanceOf[c.universe.Type]
      val gpbType = weakTypeOf[Gpb]

      val tree =
        q""" {
          import com.avast.cactus.CactusFailure
          import com.avast.cactus.CactusMacros._

          import org.scalactic._
          import org.scalactic.Accumulation._

          import scala.util.Try
          import scala.collection.JavaConverters._

          Good(${CaseClassToGpb.createConverter(c)(caseClassType, gpbType, variableName)}).orBad[org.scalactic.Every[com.avast.cactus.CactusFailure]]
         }
        """

      if (Debug) println(tree)

      tree
    }
  }

  private object GpbToCaseClass {

    def createConverter(c: whitebox.Context)(caseClassType: c.universe.Type, gpbType: c.universe.Type, gpb: c.Tree): c.Tree = {
      import c.universe._

      val i = initialize(c)(caseClassType, gpbType)
      import i._

      if (Debug) {
        println(s"Converting ${gpbType.typeSymbol} to ${caseClassType.typeSymbol}")
      }

      val params = fields.map { field =>
        val e = extractField(c)(field, gpbGetters)
        import e._

        val query = TermName(s"has$upper")

        val value = processEndType(c)(fieldName, annotations, nameInGpb, dstType, gpbType)(Some(q"$gpb.$query"), q"$gpb.$gpbGetter", gpbGetter.returnType)

        c.Expr(q"val $fieldName: $dstType Or Every[CactusFailure] = { $value }")
      }

      val fieldNames = fields.map(_.name.toTermName)

      q"""
         {
            ..$params

            withGood(..$fieldNames) { ${caseClassSymbol.companion}.apply }
         }
       """
    }


    private def processEndType(c: whitebox.Context)
                              (fieldName: c.universe.TermName, fieldAnnotations: Map[String, Map[String, String]], nameInGpb: String, returnType: c.universe.Type, gpbType: c.universe.Type)
                              (query: Option[c.universe.Tree], getter: c.universe.Tree, getterReturnType: c.universe.Type): c.Tree = {
      import c.universe._

      val dstResultType = returnType.resultType
      val srcResultType = getterReturnType.resultType

      val srcTypeSymbol = srcResultType.typeSymbol
      val dstTypeSymbol = dstResultType.typeSymbol

      dstResultType.toString match {
        case OptPattern(t) => // Option[T]
          val typeArg = dstResultType.typeArgs.head // it's an Option, so it has 1 type arg

          q""" {
                 val value: $typeArg Or Every[CactusFailure] = {${processEndType(c)(fieldName, fieldAnnotations, nameInGpb, typeArg, gpbType)(query, getter, getterReturnType)}}

                 value.map(Option(_)).recover(_ => None)
               }
           """

        case t if caseClassAndGpb(c)(dstTypeSymbol, getterReturnType) => // GPB -> case class
          if (Debug) {
            println(s"Internal case class $srcTypeSymbol, GPB type: $getterReturnType")
          }

          val conv = q" ${createConverter(c)(returnType, getterReturnType, q"$getter ")} "

          query match {
            case Some(q) => q" if ($q) $conv else Bad(One(MissingFieldFailure($nameInGpb))) "
            case None => conv
          }


        case t if isScalaMap(c)(dstTypeSymbol) =>

          fieldAnnotations.find { case (key, _) => key == classOf[GpbMap].getName } match {
            case Some((_, annot)) =>
              val keyFieldName = annot.getOrElse("key", c.abort(c.enclosingPosition, s"GpbMap annotation need 'key' to be filled in"))
              val valueFieldName = annot.getOrElse("value", c.abort(c.enclosingPosition, s"GpbMap annotation need 'key' to be filled in"))

              if (Debug) {
                println(s"Converting $srcTypeSymbol to Map from message with key = '$keyFieldName' and value = '$valueFieldName'")
              }

              val dstTypeArgs = dstResultType.typeArgs

              val (dstKeyType, dstValueType) = (dstTypeArgs.head, dstTypeArgs.tail.head)

              val getKeyField = TermName("get" + firstUpper(keyFieldName))
              val getValueField = TermName("get" + firstUpper(valueFieldName))


              val gpbGenType = extractGpbGenType(c)(getterReturnType)

              val srcKeyType = gpbGenType.member(getKeyField).asMethod.returnType
              val srcValueType = gpbGenType.member(getValueField).asMethod.returnType

              val keyField = if (srcKeyType != dstKeyType) {
                q" CactusMacros.AToB[$srcKeyType, $dstKeyType](f.$getKeyField) "
              } else {
                q" f.$getKeyField "
              }

              val valueField = if (srcValueType != dstValueType) {
                q" CactusMacros.AToB[$srcValueType, $dstValueType](f.$getValueField) "
              } else {
                q" f.$getValueField "
              }

              q"""
                Good($getter.asScala.map(f => $keyField -> $valueField ).toMap)
               """

            case None =>
              if (Debug) {
                println(s"Map field $fieldName without annotation, fallback to raw conversion")
              }

              q" CactusMacros.AToB[$srcResultType, $dstResultType]($getter) "
          }

        case t if isScalaCollection(c)(dstTypeSymbol) || dstTypeSymbol.name == TypeName("Array") => // collection

          if (isJavaCollection(c)(srcTypeSymbol)) {
            (dstResultType.typeArgs.headOption, srcResultType.typeArgs.headOption) match {
              case (Some(dstTypeArg), srcTypeArgOpt) =>
                val vectorTypeSymbol = typeOf[Vector[_]].typeSymbol

                val toFinalCollection = if (dstTypeSymbol != vectorTypeSymbol && !vectorTypeSymbol.asClass.baseClasses.contains(dstTypeSymbol)) {
                  q" CactusMacros.AToB[Vector[$dstTypeArg],${dstTypeSymbol.name.toTypeName}[$dstTypeArg]] "
                } else {
                  q" identity "
                }

                val srcTypeArg = srcTypeArgOpt.getOrElse(typeOf[String]) // TODO improve the check whether it's really a ProtocolStringList

                if (srcTypeArg == dstTypeArg) {
                  q" Good($toFinalCollection($getter.asScala.toVector)) "
                } else {

                  q"""
                     {
                       val conv = (a: $srcTypeArg) =>  {${processEndType(c)(fieldName, fieldAnnotations, nameInGpb, dstTypeArg, srcTypeArg)(None, q" a ", srcTypeArg)}}

                       $getter.asScala.map(conv).toVector.combined.map($toFinalCollection)
                     }

                   """
                }

              case (_, _) =>
                val getterGenType = extractGpbGenType(c)(getterReturnType)

                if (Debug) {
                  println(s"Converting $srcResultType to $dstResultType, fallback to raw conversion")
                }

                q" Good(CactusMacros.AToB[Vector[$getterGenType], $dstResultType]($getter.asScala.toVector)) "
            }
          } else {
            if (Debug) {
              println(s"Converting $srcResultType to $dstResultType, fallback to raw conversion")
            }

            q" Good(CactusMacros.AToB[$srcResultType, $dstResultType]($getter)) "
          }

        case t if isJavaCollection(c)(srcTypeSymbol) => // this means raw conversion, because otherwise it would have match before

          q" Good(CactusMacros.AToB[$srcResultType, $dstResultType]($getter)) "

        case t => // plain type

          val value = if (srcTypeSymbol != dstTypeSymbol) {
            if (Debug) {
              println(s"Requires converter from $srcTypeSymbol to $dstTypeSymbol")
            }

            q" CactusMacros.AToB[$srcResultType, $dstResultType]($getter) "
          } else
            q" $getter "

          query match {
            case Some(q) => q" if ($q) Good($value) else Bad(One(MissingFieldFailure($nameInGpb))) "
            case None => q" Good($value)"
          }
      }
    }
  }

  private object CaseClassToGpb {
    def createConverter(c: whitebox.Context)(caseClassType: c.universe.Type, gpbType: c.universe.Type, caseClass: c.Tree): c.Tree = {
      import c.universe._

      val i = initialize(c)(caseClassType, gpbType)
      import i._

      val gpbClassSymbol = gpbType.typeSymbol.asClass

      if (Debug) {
        println(s"Converting ${caseClassType.typeSymbol} to ${gpbType.typeSymbol}")
      }

      val params = fields.map { field =>
        val e = extractField(c)(field, gpbGetters)
        import e._

        val setter = TermName(s"set$upper")

        val assignment = processEndType(c)(q"$caseClass.$fieldName", annotations, dstType)(gpbType, gpbGetter.returnType, q"builder.$setter", upper)

        c.Expr(q" $assignment ")
      }

      q"""
        {
          val builder = ${gpbClassSymbol.companion}.newBuilder()

          ..$params

          builder.build()
        }
       """
    }

    private def processEndType(c: whitebox.Context)
                              (field: c.universe.Tree, fieldAnnotations: Map[String, Map[String, String]], srcReturnType: c.universe.Type)
                              (gpbType: c.universe.Type, getterReturnType: c.universe.Type, setter: c.universe.Tree, upperFieldName: String): c.Tree = {
      import c.universe._

      //      val getterReturnType = gpbGetterMethod.returnType

      val srcResultType = srcReturnType.resultType
      val dstResultType = getterReturnType.resultType

      val dstTypeSymbol = dstResultType.typeSymbol
      val srcTypeSymbol = srcResultType.typeSymbol

      srcResultType.toString match {
        case OptPattern(t) => // Option[T]
          val typeArg = srcResultType.typeArgs.head // it's an Option, so it has 1 type arg

          q" $field.foreach(value => ${processEndType(c)(q"value", fieldAnnotations, typeArg)(gpbType, getterReturnType, setter, upperFieldName)}) "

        case t if caseClassAndGpb(c)(srcTypeSymbol, getterReturnType) => // case class -> GPB

          q" $setter(${createConverter(c)(srcResultType, getterReturnType, q" $field ")}) "

        case t if isScalaMap(c)(srcTypeSymbol) => // Map[A, B]
          val addMethod = TermName(s"addAll$upperFieldName")

          fieldAnnotations.find { case (key, _) => key == classOf[GpbMap].getName } match {
            case Some((_, annot)) =>
              val keyFieldName = annot.getOrElse("key", c.abort(c.enclosingPosition, s"GpbMap annotation need 'key' to be filled in"))
              val valueFieldName = annot.getOrElse("value", c.abort(c.enclosingPosition, s"GpbMap annotation need 'key' to be filled in"))

              if (Debug) {
                println(s"Converting Map to $dstTypeSymbol - message with key = '$keyFieldName' and value = '$valueFieldName'")
              }

              val srcTypeArgs = srcResultType.typeArgs

              val (srcKeyType, srcValueType) = (srcTypeArgs.head, srcTypeArgs.tail.head)

              val getKeyField = TermName("get" + firstUpper(keyFieldName))
              val getValueField = TermName("get" + firstUpper(valueFieldName))


              val gpbGenType = extractGpbGenType(c)(getterReturnType)

              val dstKeyType = gpbGenType.member(getKeyField).asMethod.returnType
              val dstValueType = gpbGenType.member(getValueField).asMethod.returnType

              val keyField = if (srcKeyType != dstKeyType) {
                q" CactusMacros.AToB[$srcKeyType, $dstKeyType](key) "
              } else {
                q" key "
              }

              val valueField = if (srcValueType != dstValueType) {
                q" CactusMacros.AToB[$srcValueType, $dstValueType](value) "
              } else {
                q" value "
              }

              val mapGpb = gpbGenType.companion

              q"""
                ${TermName("builder")}.$addMethod(
                  $field.map{ _ match {
                  case (key, value) =>
                    $mapGpb.newBuilder()
                    .${TermName("set" + firstUpper(keyFieldName))}($keyField)
                    .${TermName("set" + firstUpper(valueFieldName))}($valueField)
                    .build()}
                  }.asJavaCollection
                )
               """

            case None =>
              if (Debug) {
                println(s"Map field $field without annotation, fallback to raw conversion")
              }

              q" CactusMacros.AToB[$srcResultType, $dstResultType]($field) "
          }

        case t if isScalaCollection(c)(srcTypeSymbol) || srcTypeSymbol.name == TypeName("Array") => // collection

          val l = if (upperFieldName.endsWith("List")) upperFieldName.substring(0, upperFieldName.length - 4) else upperFieldName
          val addMethod = TermName(s"addAll$l")

          val getterGenType = extractGpbGenType(c)(getterReturnType)

          if (isJavaCollection(c)(dstTypeSymbol)) {

            (dstResultType.typeArgs.headOption, srcResultType.typeArgs.headOption) match {
              case (Some(dstTypeArg), srcTypeArgOpt) =>

                val srcTypeArg = srcTypeArgOpt.getOrElse(typeOf[String]) // TODO improve the check whether it's really a ProtocolStringList

                if (srcTypeArg == dstTypeArg) {
                  q" $field.toSeq "
                } else {

                  q"""
                                   val conv = (a: $srcTypeArg) =>  { ${processEndType(c)(q"a", fieldAnnotations, srcTypeArg)(dstTypeArg, dstTypeArg, q"identity", " a ")} }


                                   ${TermName("builder")}.$addMethod($field.toSeq.map(conv).asJava)

                                """
                }

              case (_, _) =>

                q" ${TermName("builder")}.$addMethod(CactusMacros.AToB[Seq[$getterGenType], Seq[$getterGenType]]($field).asJava) "

            }

          } else {
            if (Debug) {
              println(s"Converting $srcResultType to $dstResultType, fallback to raw conversion")
            }

            q"""
              ${TermName("builder")}.$addMethod(CactusMacros.AToB[$srcResultType, $dstResultType]($field))
           """
          }

        case t if isJavaCollection(c)(dstTypeSymbol) => // this means raw conversion, because otherwise it would have match before

          val l = if (upperFieldName.endsWith("List")) upperFieldName.substring(0, upperFieldName.length - 4) else upperFieldName
          val addMethod = TermName(s"addAll$l")

          q"""
              ${TermName("builder")}.$addMethod(CactusMacros.AToB[$srcResultType, $dstResultType]($field))
           """

        case t => // plain type

          val value = if (srcResultType != dstResultType) {
            if (Debug) {
              println(s"Requires converter from $srcTypeSymbol to $dstTypeSymbol")
            }

            q" CactusMacros.AToB[$srcResultType, $dstResultType]($field) "
          } else q" $field "

          q" $setter($value) "
      }
    }

  }

  private def initialize(c: whitebox.Context)(caseClassType: c.universe.Type, gpbType: c.universe.Type) = new {

    import c.universe._

    if (!caseClassType.typeSymbol.isClass) {
      c.abort(c.enclosingPosition, s"Provided type $caseClassType is not a class")
    }

    val caseClassSymbol = caseClassType.typeSymbol.asClass

    if (!caseClassSymbol.isCaseClass) {
      c.abort(c.enclosingPosition, s"Provided type $caseClassType is not a case class")
    }

    val ctor = caseClassType.decls.collectFirst {
      case m: MethodSymbol if m.isPrimaryConstructor => m
    }.get

    val fields = ctor.paramLists.flatten

    val gpbGetters = gpbType.decls.collect {
      case m: MethodSymbol if m.name.toString.startsWith("get") && !m.isStatic => m
    }
  }

  private def extractField(c: whitebox.Context)(field: c.universe.Symbol, gpbGetters: Iterable[c.universe.MethodSymbol]) = new {

    import c.universe._

    val fieldName = field.name.decodedName.toTermName
    val dstType = field.typeSignature

    val annotsTypes = field.annotations.map(_.tree.tpe.toString)
    val annotsParams = field.annotations.map {
      _.tree.children.tail.map { case q" $name = $value " =>
        name.toString() -> c.eval[String](c.Expr(q"$value"))
      }.toMap
    }

    val annotations = annotsTypes.zip(annotsParams).toMap

    val gpbNameAnnotations = annotations.find { case (key, _) => key == classOf[GpbName].getName }

    val nameInGpb = gpbNameAnnotations.flatMap { case (_, par) =>
      par.get("value")
    }.map(_.toString()).getOrElse(fieldName.toString)

    val upper = firstUpper(nameInGpb.toString)

    // find getter for the field in GPB
    // try *List first for case it's a repeated field and user didn't name it *List in the case class
    val gpbGetter = gpbGetters
      .find(_.name.toString == s"get${upper}List") // collection ?
      .orElse(gpbGetters.find(_.name.toString == s"get$upper"))
      .getOrElse {
        if (Debug) {
          println(s"No getter for $fieldName found in GPB - neither ${s"get${upper}List"} nor ${s"get$upper"}")
          println(s"All getters: ${gpbGetters.map(_.name.toString).mkString("[", ", ", "]")}")
        }

        c.abort(c.enclosingPosition, s"Could not find getter in GPB for field $nameInGpb ($fieldName in case class), does the field in GPB exist?")
      }

  }

  private def extractGpbGenType(c: whitebox.Context)(getterReturnType: c.universe.Type) = {
    import c.universe._

    val getterResultType = getterReturnType.resultType
    val getterGenType = getterResultType.typeArgs.headOption
      .getOrElse {
        if (getterResultType.toString == "com.google.protobuf.ProtocolStringList") {
          typeOf[java.lang.String]
        } else {
          c.abort(c.enclosingPosition, s"Could not extract generic type from $getterResultType")
        }
      }

    getterGenType
  }

  private def extractSymbolFromClassTag[CaseClass: c.WeakTypeTag](c: whitebox.Context)(gpbCt: c.Tree) = {
    import c.universe._

    (gpbCt match {
      case q"ClassTag.apply[$cl](${_}): ${_}" => cl

      case t => c.abort(c.enclosingPosition, s"Cannot process the conversion - variable type extraction from tree '$t' failed")
    }).symbol
  }

  private def getVariableName[Gpb: c.WeakTypeTag](c: whitebox.Context): c.universe.Tree = {
    import c.universe._

    val variableName = c.prefix.tree match {
      case q"cactus.this.`package`.${_}[${_}]($n)" => n
      case q"com.avast.cactus.`package`.${_}[${_}]($n)" => n

      case t => c.abort(c.enclosingPosition, s"Cannot process the conversion - variable name extraction from tree '$t' failed")
    }

    q" $variableName "
  }

  private def caseClassAndGpb(c: whitebox.Context)(caseClassTypeSymbol: c.universe.Symbol, getterReturnType: c.universe.Type): Boolean = {
    caseClassTypeSymbol.isClass && caseClassTypeSymbol.asClass.isCaseClass && getterReturnType.baseClasses.map(_.name.toString).contains("MessageLite")
  }

  private def isScalaCollection(c: whitebox.Context)(typeSymbol: c.universe.Symbol): Boolean = {
    typeSymbol.isClass && typeSymbol.asClass.baseClasses.map(_.fullName.toString).contains("scala.collection.TraversableLike")
  }

  private def isJavaCollection(c: whitebox.Context)(typeSymbol: c.universe.Symbol): Boolean = {
    typeSymbol.isClass && typeSymbol.asClass.baseClasses.map(_.fullName.toString).contains("java.util.Collection")
  }

  private def isScalaMap(c: whitebox.Context)(typeSymbol: c.universe.Symbol): Boolean = {
    import c.universe._

    isScalaCollection(c)(typeSymbol) && typeSymbol.name == TypeName("Map")
  }

  private def firstUpper(s: String): String = {
    s.charAt(0).toUpper + s.substring(1)
  }

}
