package io.univalence.sparktest

import io.univalence.typedpath.{ ArrayPath, FieldPath, Path, PathOrRoot, Root }
import org.apache.spark.sql.types.{ ArrayType, DataType, StructField, StructType }

import scala.util.{ Failure, Try }

object SchemaComparison {

  sealed trait FieldModification

  final case class AddField(dataType: DataType) extends FieldModification

  final case class RemoveField(dataType: DataType) extends FieldModification

  final case class ChangeFieldType(from: DataType, to: DataType) extends FieldModification

  //final case object SetNullable extends FieldModification

  //final case object SetNonNullable extends FieldModification

  final case class SchemaModification(path: Path, fieldModification: FieldModification)

  /**
    * Compare two schemas returning differences between both
    *
    * Example :
    * val sc1 = struct("number" -> int, "name" -> int)
    * val sc2 = struct("rebmun" -> int, "name" -> double)
    * compareSchema(sc1, sc2)
    *
    * Result :
    * Seq(
    *   SchemaModification(path"number", RemoveField(int)),
    *   SchemaModification(path"name", ChangeFieldType(int, double)),
    *   SchemaModification(path"rebmun", AddField(int))
    * )
    *
    * @param sc1 The entry schema
    * @param sc2 Schema to compare to
    * @return    Rhe sequence of schema modification from sc1 to s2
    */
  def compareSchema(sc1: StructType, sc2: StructType): Seq[SchemaModification] = {

    def compareSchema(sc1: StructType, sc2: StructType, prefix: PathOrRoot): Seq[SchemaModification] = {
      def compareDataType(d1: DataType, d2: DataType, prefix: Path): Seq[SchemaModification] =
        (d1, d2) match {
          case (a, b) if a == b                 => Nil
          case (s1: StructType, s2: StructType) => compareSchema(s1, s2, prefix)
          case (a1: ArrayType, a2: ArrayType)   => compareDataType(a1.elementType, a2.elementType, ArrayPath(prefix))
          case (a, b)                           => Seq(SchemaModification(prefix, ChangeFieldType(a, b)))
        }

      val allFields = (sc1.fieldNames ++ sc2.fieldNames).distinct

      for {
        fieldname <- allFields

        left: Option[StructField]  = sc1.fields.find(_.name == fieldname)
        right: Option[StructField] = sc2.fields.find(_.name == fieldname)
        path: Path                 = FieldPath(FieldPath.createName(fieldname).get, prefix)

        modifications: Seq[SchemaModification] = (left, right) match {
          case (Some(l), None)    => Seq(SchemaModification(path, RemoveField(l.dataType)))
          case (None, Some(r))    => Seq(SchemaModification(path, AddField(r.dataType)))
          case (None, None)       => Nil
          case (Some(l), Some(r)) => compareDataType(l.dataType, r.dataType, path)
        }

        modification <- modifications
      } yield modification
    }

    compareSchema(sc1, sc2, Root)
  }

  case class ApplyModificationErrorWithSource(error: ApplyModificationError,
                                              sc: StructType,
                                              schemaModification: SchemaModification)
      extends Exception(error)

  sealed trait ApplyModificationError extends Exception
  case class DuplicatedField(name: String) extends ApplyModificationError
  case class NotFoundField(name: String) extends ApplyModificationError
  case object UnreachablePath extends ApplyModificationError

  /**
    * Apply a modification to a schema
    *
    * Example :
    * val sc = struct("number" -> int)
    * val sm = SchemaModification(path"rebmun", AddField(int))
    * modifySchema(sc, sm)
    *
    * Result :
    * Success(struct("number" -> int, "rebmun" -> int)
    *
    * @param sc                  The entry schema
    * @param schemaModification  A schema modication that must be applied to the entry schema
    * @return                    The schema after the modification
    */
  def modifySchema(sc: StructType, schemaModification: SchemaModification): Try[StructType] = {

    def loopDt(dataType: DataType, paths: List[Path], fieldModification: FieldModification): DataType =
      dataType match {
        case st: StructType => loopSt(st, paths, fieldModification)
        case at: ArrayType =>
          paths match {
            case ArrayPath(_) :: xs => ArrayType(loopDt(at.elementType, xs, fieldModification))
            case _                  => throw UnreachablePath
          }
        case _ =>
          fieldModification match {
            case ChangeFieldType(_, to) => to
            case _                      => throw UnreachablePath

          }
      }

    def applyN[A](f: A => A, n: Int): A => A =
      if (n <= 0) identity else f andThen applyN(f, n - 1)

    def loopSt(sc: StructType, paths: List[Path], fieldModification: FieldModification): StructType =
      (paths, fieldModification) match {
        case (List(FieldPath(name, _)), AddField(dt)) =>
          if (!sc.fieldNames.contains(name)) StructType(sc.fields :+ StructField(name, dt))
          else throw DuplicatedField(name)

        case (List(FieldPath(name, _)), RemoveField(_)) =>
          if (sc.fieldNames.contains(name)) StructType(sc.filter(field => field.name != name))
          else throw NotFoundField(name)

        case (FieldPath(name, _) :: xs, ChangeFieldType(_, to)) if xs.forall(_.isInstanceOf[ArrayPath]) =>
          if (sc.fieldNames.contains(name))
            StructType(
              sc.map(
                field =>
                  if (field.name == name) field.copy(dataType = applyN[DataType](ArrayType.apply, xs.size)(to))
                  else field
              )
            )
          else
            throw NotFoundField(name)

        //case (FieldPath(name, _) :: xs, action) if xs.forall(_.isInstanceOf[ArrayPath]) =>

        case (FieldPath(name, _) :: xs, action) if xs.nonEmpty =>
          if (sc.fieldNames.contains(name))
            StructType(sc.fields.map(x => if (x.name == name) StructField(name, loopDt(x.dataType, xs, action)) else x))
          else
            throw NotFoundField(name)
      }

    Try(loopSt(sc, schemaModification.path.allPaths, schemaModification.fieldModification)).recoverWith({
      case a: ApplyModificationError =>
        val source = ApplyModificationErrorWithSource(a, sc, schemaModification)
        source.getCause
        Failure(source)
    })

  }
}
