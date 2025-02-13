package io.getquill.util

import java.io.PrintStream

import io.getquill.AstPrinter
import io.getquill.AstPrinter.Implicits._
import io.getquill.util.Messages.TraceType
import io.getquill.util.IndentUtil._

import scala.collection.mutable
import scala.util.matching.Regex

// Copy of Interpolator in Scala2-Quill various things fixed. Shuold look into merging back.
class Interpolator2(
    traceType: TraceType,
    defaultIndent: Int = 0,
    color: Boolean = Messages.traceColors,
    qprint: AstPrinter = Messages.qprint,
    out: PrintStream = System.out,
    tracesEnabled: (TraceType) => Boolean = Messages.tracesEnabled(_)
) {
  implicit class InterpolatorExt(sc: StringContext) {
    def trace(elements: Any*) = new Traceable(sc, elements)
  }

  class Traceable(sc: StringContext, elementsSeq: Seq[Any]) {

    private val elementPrefix = "|  "

    private sealed trait PrintElement
    private case class Str(str: String, first: Boolean) extends PrintElement
    private case class Elem(value: String) extends PrintElement
    private case class Simple(value: String) extends PrintElement
    private case object Separator extends PrintElement

    extension (str: String)
      def reallyFitsOnOneLine: Boolean = {
        val output = !str.contains("\n") && !str.contains("\r")
        // println(s"*********************** STRING FITS ONE ONE LINE = ${output} - ${str}")
        output
      }
      def reallyMultiline(indent: Int, prefix: String, prependNewline: Boolean = false): String =
        // Split a string and indent.... if it is actually multi-line. Since this typically is used
        // on parts of strings of a parent-string which may be multline, but the individual elements
        // might not be which results in strange things like:
        //    (Node Option) ['mt] Mapping: asExprOf:       |lastName      | into       |String      | in       |(
        //    |    (optField: Option[LastNameAge]) =>
        //    |      optField.map[String](((prop: LastNameAge) => prop.lastName))
        //    |)      |
        val prepend = if (prependNewline) "\n" else ""
        if (str.contains("\n"))
          prepend + str.split("\n").map(elem => indent.prefix + prefix + elem).mkString("\n")
        else
          str

    private def generateStringForCommand(value: Any, indent: Int) = {
      val objectString = qprint(value).string(color)
      val oneLine = objectString.reallyFitsOnOneLine
      oneLine match {
        case true => s"${indent.prefix}> ${objectString}"
        case false =>
          s"${indent.prefix}>\n${objectString.reallyMultiline(indent, elementPrefix)}"
      }
    }

    private def readFirst(first: String) =
      new Regex("%([0-9]+)(.*)").findFirstMatchIn(first) match {
        case Some(matches) =>
          (matches.group(2).trim, Some(matches.group(1).toInt))
        case None => (first, None)
      }

    sealed trait Splice { def value: String }
    object Splice {
      case class Simple(value: String) extends Splice // Simple splice into the string, don't indent etc...
      case class Show(value: String) extends Splice // Indent, colorize the element etc...
    }

    private def readBuffers() = {
      def orZero(i: Int): Int = if (i < 0) 0 else i

      val parts = sc.parts.iterator.toList
      val elements = elementsSeq.toList.map(elem => {
        if (elem.isInstanceOf[String]) Splice.Simple(elem.asInstanceOf[String])
        else Splice.Show(qprint(elem).string(color))
      })

      val (firstStr, explicitIndent) = readFirst(parts.head)
      val indent =
        explicitIndent match {
          case Some(value) => value
          case None => {
            // A trick to make nested calls of andReturn indent further out which makes andReturn MUCH more usable.
            // Just count the number of times it has occurred on the thread stack.
            val returnInvocationCount = Thread
              .currentThread()
              .getStackTrace
              .toList
              .count(e => e.getMethodName.contains("andReturn") || e.getMethodName.contains("andContinue"))
            defaultIndent + orZero(returnInvocationCount - 1) * 2
          }
        }

      val partsIter = parts.iterator
      partsIter.next() // already took care of the 1st element
      val elementsIter = elements.iterator

      val sb = new mutable.ArrayBuffer[PrintElement]()
      sb.append(Str(firstStr, true))

      while (elementsIter.hasNext) {
        val nextElem = elementsIter.next()
        nextElem match {
          case Splice.Simple(v) =>
            sb.append(Simple(v))
            val nextPart = partsIter.next()
            sb.append(Simple(nextPart))
          case Splice.Show(v) =>
            sb.append(Separator)
            sb.append(Elem(v))
            val nextPart = partsIter.next()
            sb.append(Separator)
            sb.append(Str(nextPart, false))
        }
      }

      (sb.toList, indent)
    }

    def generateString() = {
      val (elementsRaw, indent) = readBuffers()

      val elements = elementsRaw.filter {
        case Str(value, _) => value.trim != ""
        case Elem(value)   => value.trim != ""
        case _             => true
      }

      val oneLine = elements.forall {
        case Simple(value) => value.reallyFitsOnOneLine
        case Elem(value)   => value.reallyFitsOnOneLine
        case Str(value, _) => value.reallyFitsOnOneLine
        case _             => true
      }

      val output =
        elements.map {
          case Simple(value) if (oneLine)     => value
          case Str(value, true) if (oneLine)  => indent.prefix + value
          case Str(value, false) if (oneLine) => value
          case Elem(value) if (oneLine)       => value
          case Separator if (oneLine)         => " "
          case Simple(value)                  => value.reallyMultiline(indent, "|", true)
          case Str(value, true)               => indent.prefix + value.reallyMultiline(indent, "", true)
          case Str(value, false)              => value.reallyMultiline(indent, "|", true)
          case Elem(value)                    => value.reallyMultiline(indent, "|  ", true)
          case Separator                      => "\n"
        }

      (output.mkString, indent)
    }

    private def logIfEnabled[T]() =
      if (tracesEnabled(traceType))
        Some(generateString())
      else
        None

    def andLog(): Unit =
      logIfEnabled().foreach(value => out.println(value._1))

    def andContinue[T](command: => T) = {
      logIfEnabled().foreach(value => out.println(value._1))
      command
    }

    def andReturn[T](command: => T) = {
      logIfEnabled() match {
        case Some((output, indent)) =>
          // do the initial log
          out.println(output)
          // evaluate the command, this will activate any traces that were inside of it
          val result = command
          out.println(generateStringForCommand(result, indent))

          result
        case None =>
          command
      }
    }

    def andReturnLog[T, L](command: => (T, L)) = {
      logIfEnabled() match {
        case Some((output, indent)) =>
          // do the initial log
          out.println(output)
          // evaluate the command, this will activate any traces that were inside of it
          val (result, logData) = command
          out.println(generateStringForCommand(logData, indent))

          result
        case None =>
          command
      }
    }
  }
}
