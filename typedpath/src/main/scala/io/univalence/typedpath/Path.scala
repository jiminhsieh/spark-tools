package io.univalence.typedpath

import io.univalence.typedpath.Index.{ ArrayIndex, FieldIndex }

import scala.language.experimental.macros
import scala.reflect.macros.whitebox
import scala.util.matching.Regex
import scala.util.{ Failure, Success, Try }

object PathMacro {

  //interleave(Seq(poteau, poteau, poteau), Seq(cloture,cloture)) == Seq(poteau, cloture, poteau, cloture, poteau)
  def interleave[A, B](xa: Seq[A], xb: Seq[B]): Seq[Either[A, B]] = {
    def go(xa: Seq[A], xb: Seq[B], accc: Vector[Either[A, B]]): Vector[Either[A, B]] =
      (xa, xb) match {
        case (Seq(), _)                         => accc
        case (Seq(x, _ @_*), Seq())             => accc :+ Left(x)
        case (Seq(a, as @ _*), Seq(b, bs @ _*)) => go(as, bs, accc :+ Left(a) :+ Right(b))
      }
    go(xa, xb, Vector.empty)
  }

  def pathMacro(c: whitebox.Context)(args: c.Expr[PathOrRoot]*): c.Expr[Path] = {
    import c.universe._

    def lit(s: String): c.Expr[String] = c.Expr[String](Literal(Constant(s)))

    val strings: List[String] = {
      //pattern matching pour récupérer les chaines de du string contexte
      //val q"$x($y(...$rawParts))" = c.prefix.tree
      val Apply(_, List(Apply(_, rawParts))) = c.prefix.tree
      rawParts.map({
        case Literal(Constant(y: String)) => y
      })
    }

    val allParts: Seq[Either[String, c.Expr[PathOrRoot]]] = interleave(strings, args)

    def create(string: String, base: c.Expr[PathOrRoot]): c.Expr[PathOrRoot] =
      if (string.isEmpty) base
      else {

        import Path._
        val tokens: Seq[Token] = Token.tokenize(string)
        if (tokens.exists(_.isInstanceOf[Path.ErrorToken])) {

          val error = Path.highlightErrors(tokens: _*)
          c.abort(c.enclosingPosition, s"invalid path $error")
        } else {

          val validTokens: Seq[Path.ValidToken] = tokens.collect({ case s: Path.ValidToken => s })

          validTokens.foldLeft[c.Expr[PathOrRoot]](
            base
          )({
            case (parent, Path.NamePart(name)) =>
              //improve checks on name
              reify(FieldPath(lit(name).splice, parent.splice).get)
            case (parent, Path.Dot) => parent
            case (parent, Path.Slash) if c.typecheck(parent.tree).tpe <:< typeOf[Path] =>
              reify(ArrayPath(parent.splice.asInstanceOf[Path]))

            case (parent, Path.Slash) =>
              c.abort(
                c.enclosingPosition,
                s"${Option(parent.actualType).getOrElse("")} can't create array from root : ${parent.tree} $string"
              )

          })

        }

      }

    if (!allParts.exists({
          case Left(x)  => x.nonEmpty
          case Right(p) => p.actualType <:< typeOf[Path]
        })) {
      val text = "can't turn into a NonEmptyPath. Use case object Root instead if you want to target the Root."
      c.abort(
        c.enclosingPosition,
        s"path [${allParts.filterNot(_.left.exists(_.isEmpty)).map(_.fold(identity, _.tree)).mkString(" - ")}] $text"
      )
    }

    //.foldLeft[c.Expr[Path]] ...
    val res = allParts.foldLeft[c.Expr[PathOrRoot]](reify(Root))({
      case (base, Left("")) => base
      case (base, Left(x))  => create(x, base)
      case (base, Right(x)) => {} match {
        case _ if x.actualType == typeOf[ArrayPath] =>
          reify(Path.combineToArray(base.splice, x.splice.asInstanceOf[ArrayPath]))
        case _ if x.actualType == typeOf[FieldPath] =>
          reify(Path.combineToField(base.splice, x.splice.asInstanceOf[FieldPath]))
        case _ =>
          reify(Path.combineToPath(base.splice, x.splice))
      }
    })

    res.asInstanceOf[c.Expr[Path]]
  }
}

sealed trait PathOrRoot

object Path {

  sealed trait Token

  sealed trait ValidToken extends Token
  case object Dot extends ValidToken // "."
  case object Slash extends ValidToken //  "/"
  case class NamePart(name: String) extends ValidToken // "[a-zA-Z0-9_]+"
  case class ErrorToken(part: String) extends Token

  object Token {
    object Pattern {
      case class StartWithPattern(pattern: String) {
        val regex: Regex = ("^(" + pattern + ")(.*?)$").r
        def unapply(string: String): Option[(String, String)] = string match {
          case regex(m, r) => Some((m, r))
          case _           => None
        }
      }
      case class StartWithChar(char: Char) {
        def unapply(string: String): Option[String] =
          if (string.nonEmpty && string.head == char) Some(string.tail) else None
      }

      val Dot   = StartWithChar('.')
      val Slash = StartWithChar('/')
      val Name  = StartWithPattern("\\w+")
      val Error = StartWithPattern("[^\\w/.]+")
    }

    val tokenOf: String => Option[(Token, String)] = {
      case ""                         => None
      case Pattern.Dot(rest)          => Some((Dot, rest))
      case Pattern.Slash(rest)        => Some((Slash, rest))
      case Pattern.Name(name, rest)   => Some((NamePart(name), rest))
      case Pattern.Error(error, rest) => Some((ErrorToken(error), rest))
      case error                      => Some((ErrorToken(error), ""))
    }

    //probablement dans catz ou scalas
    def unfold[A, S](z: S)(f: S => Option[(A, S)]): Vector[A] = {
      def go(z: S, acc: Vector[A]): Vector[A] =
        f(z) match {
          case None         => acc
          case Some((a, s)) => go(s, acc :+ a)
        }
      go(z, Vector.empty)
    }

    val tokenize: String => Seq[Token] = unfold(_)(tokenOf)

    def stringify(tokens: Token*): String =
      if (tokens.isEmpty) ""
      else if (tokens.size == 1) tokens.head match {
        case Dot               => "."
        case Slash             => "/"
        case NamePart(name)    => name
        case ErrorToken(error) => error
      } else tokens.map(x => stringify(x)).mkString
  }

  def highlightErrors(tokens: Token*): String =
    tokens
      .map({
        case ErrorToken(part) => s"[$part]"
        case x                => Token.stringify(x)
      })
      .mkString

  def combineToPath(prefix: PathOrRoot, suffix: PathOrRoot): PathOrRoot =
    suffix match {
      case Root         => prefix
      case f: FieldPath => combineToField(prefix, f)
      case a: ArrayPath => combineToArray(prefix, a)
    }

  def combineToArray(prefix: PathOrRoot, suffix: ArrayPath): ArrayPath =
    ArrayPath(combineToPath(prefix, suffix.parent).asInstanceOf[Path])

  def combineToField(prefix: PathOrRoot, suffix: FieldPath): FieldPath =
    suffix.copy(parent = combineToPath(prefix, suffix.parent))

  def create(string: String): Try[PathOrRoot] = {
    val tokens = Token.tokenize(string)
    if (tokens.exists(_.isInstanceOf[ErrorToken])) {

      Failure(
        new Exception(
          s"invalid string ${highlightErrors(tokens: _*)} as a path"
        )
      )

    } else {
      val validToken = tokens.collect({ case v: ValidToken => v })

      validToken.foldLeft[Try[PathOrRoot]](Try(Root))({
        case (parent, NamePart(name)) => parent.flatMap(FieldPath(name, _))
        case (parent, Dot)            => parent //Meh c'est un peu étrange, mais par construction
        case (parent, Slash) =>
          parent.flatMap({
            case n: Path => Try(ArrayPath(n))
            case _       => Failure(new Exception(s"cannot create an array at the root $string"))
          })

      })
    }

  }
}

sealed trait IndexOrRoot

case object Root extends PathOrRoot with IndexOrRoot

sealed trait Index extends IndexOrRoot {

  final def at(name: String with FieldName): FieldIndex = FieldIndex(name, this)
  final def at(idx: Int): ArrayIndex                    = ArrayIndex(idx, this)

  final def firstName: String with FieldName =
    this match {
      case Index.ArrayIndex(_, parent)        => parent.firstName
      case Index.FieldIndex(name, Root)       => name
      case Index.FieldIndex(_, parent: Index) => parent.firstName

    }
}

object Index {

  final def apply(name: FieldName with String): FieldIndex = FieldIndex(name, Root)

  final case class FieldIndex(name: String with FieldName, parent: IndexOrRoot) extends Index {
    override def toString: String = parent.toString + "." + name
  }

  final case class ArrayIndex(idx: Int, parent: Index) extends Index {
    assert(idx > 0)
    override def toString: String = parent.toString + s"[$idx]"
  }
  //case class ArrayLastElement(parent: Index) extends Index

  def create(index: String): Try[Index] = Try {

    val parts: Array[String] = index.split('.')

    def computeIndex(parent: IndexOrRoot, part: String): IndexOrRoot = {

      val (name, idx) = part.indexOf('[') match {
        case -1 => (part, "")
        case x  => part.splitAt(x)
      }

      val index = FieldIndex(FieldPath.createName(name).get, parent)

      if (idx == null)
        index
      else {
        val Idx = "\\[(\\d+)\\]".r
        Idx
          .findAllMatchIn(idx)
          .foldLeft[Index](index)((i, s) => ArrayIndex(s.group(1).toInt, i))
      }

    }

    parts.foldLeft[IndexOrRoot](Root)(computeIndex) match {
      case Root     => throw new Exception("incorrect index from string \"" + index + "\"")
      case i: Index => i
    }

  }
}

sealed trait Path extends PathOrRoot {

  def firstName: String with FieldPath.Name =
    this match {
      case FieldPath(name, Root) => name
      case FieldPath(_, p: Path) => p.firstName
      case ArrayPath(parent)     => parent.firstName
    }

  def allPaths: List[Path] = {
    def loop(path: Path, stack: List[Path]): List[Path] =
      path match {
        case FieldPath(_, Root)         => path :: stack
        case FieldPath(_, parent: Path) => loop(parent, path :: stack)
        case ArrayPath(parent)          => loop(parent, path :: stack)
      }
    loop(this, Nil)
  }
}

case class FieldPath(name: String with FieldPath.Name, parent: PathOrRoot) extends Path {
  override def toString: String = parent match {
    case Root         => name
    case f: FieldPath => s"$f.$name"
    case a: ArrayPath => s"$a$name"
  }
}

sealed trait FieldName

object FieldPath {
  type Name = FieldName

  def createName(string: String): Try[String with Name] = {
    val regExp = "^[a-zA-Z_][a-zA-Z0-9_]*$".r
    regExp
      .findPrefixMatchOf(string)
      .fold[Try[String with Name]](Failure(new Exception(s"$string is not a valid field name")))(
        _ => Success(string.asInstanceOf[String with Name])
      )
  }

  def apply(name: String, parent: PathOrRoot): Try[FieldPath] =
    createName(name).map(new FieldPath(_, parent))
}

case class ArrayPath(parent: Path) extends Path {
  override def toString: String = s"$parent/"
}
