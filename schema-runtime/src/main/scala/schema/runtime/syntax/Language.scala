package schema.runtime
package syntax

import Language._
import Context._
import java.util.{Timer, TimerTask}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

trait Language {

  private lazy val retries = new Timer(true)

  /**
   * Asynchronously executes the transaction generated by the specified function on the underlying
   * database, retrying non-fatal failures with backoff.
   *
   * @param f Transaction generator.
   * @param ec Implicit execution context.
   * @param db Implicit database.
   * @return Result of transaction execution.
   */
  def Schema(backoffs: Seq[FiniteDuration])(f: Context => Unit)(
    implicit db: Database,
    ec: ExecutionContext
  ): Future[String] =
    Schema(f).recoverWith { case NonFatal(_) if backoffs.nonEmpty =>
      val result = Promise[String]()

      this.retries.schedule(new TimerTask {
        override def run(): Unit =
          Schema(backoffs.drop(1))(f).onComplete(result.complete)
      }, backoffs.head.toMillis)

      result.future
    }

  /**
   * Asynchronously executes the transaction generated by the specified function on the underlying
   * database.
   *
   * @param f Transaction generator.
   * @param ec Implicit execution context.
   * @param db Implicit database.
   * @return Result of transaction execution.
   */
  def Schema(f: Context => Unit)(
    implicit db: Database,
    ec: ExecutionContext
  ): Future[String] = {
    val ctx = Context.empty
    f(ctx)
    db.execute(ctx.txn)
  }

  /**
   * Retrieves the object with the specified key.
   *
   * @param key Key to lookup.
   * @param ctx Implicit transaction context.
   * @return Corresponding object.
   */
  def Select(key: Key)(
    implicit ctx: Context
  ): Object = {
    require(key.nonEmpty, "Key must be non-empty.")
    require(!key.contains(FieldDelimiter.value), s"Key may not contain ${FieldDelimiter.value}")
    require(!key.contains(ArrayDelimiter.value), s"Key may not contain ${ArrayDelimiter.value}")
    Object(key)
  }

  /**
   * Retrieves the object at the key stored in the variable.
   *
   * @param variable Variable containing key.
   * @param ctx Implicit transaction context.
   * @return Corresponding object.
   */
  def Select(variable: Variable)(
    implicit ctx: Context
  ): Object = Object(variable)

  /**
   * Removes the specified object and its various fields.
   *
   * @param obj Object to remove.
   * @param ctx Implicit transaction context.
   */
  def Delete(obj: Object)(
    implicit ctx: Context
  ): Unit = {
    // When working with loops, it is important to prefetch keys whenever possible.
    ctx += prefetch(obj.$fields)
    ctx += prefetch(obj.$indices)

    // Serialize the various fields of the object.
    If(length(obj.$fields) > 0) {
      ctx.$i = 0

      While(ctx.$i < length(obj.$fields)) {
        ctx.$j = ctx.$i + 1
        While(obj.$fields.charAt(ctx.$j) <> ArrayDelimiter) {
          ctx.$j = ctx.$j + 1
        }

        val name = obj.$fields.substring(ctx.$i, ctx.$j)
        ctx += write(obj.key ++ FieldDelimiter ++ name, Literal.Empty)
        ctx.$i = ctx.$j + 1
      }
    }

    // Serialize the various indices of the object.
    If (length(obj.$indices) > 0) {
      ctx.$i = 0

      While (ctx.$i < length(obj.$indices)) {
        ctx.$j = ctx.$i + 1
        While (obj.$indices.charAt(ctx.$j) <> ArrayDelimiter) {
          ctx.$j = ctx.$j + 1
        }

        val name = obj.$indices.substring(ctx.$i, ctx.$j)
        val field = obj.key ++ FieldDelimiter ++ name
        val index = read(field ++ FieldDelimiter ++ literal("$addresses"))
        prefetch(index)
        ctx.$k = 0

        While(ctx.$k < length(index)) {
          ctx.$l = ctx.$k + 1
          While(!equal(index.charAt(ctx.$l), ArrayDelimiter)) {
            ctx.$l = ctx.$l + 1
          }

          val at = index.substring(ctx.$k, ctx.$l)
          ctx += write(field ++ FieldDelimiter ++ at, Literal.Empty)
          ctx.$k = ctx.$l + 1
        }

        ctx += write(field ++ FieldDelimiter ++ literal("$addresses"), Literal.Empty)
        ctx.$i = ctx.$j + 1
      }
    }

    // Clean up all our hidden variables and remove the existence marker on the object.
    obj.$fields = ""
    obj.$indices = ""
    ctx += write(obj.key, Literal.Empty)
  }

  /**
   * Conditionally branches on the result of the specified condition. An else clause may be
   * optionally specified. Relies on 'import scala.language.reflectiveCalls'.
   *
   * @param condition Condition to branch on.
   * @param success Execute if condition is satisfied.
   * @param ctx Implicit transaction context.
   * @return Optional else clause.
   */
  def If(condition: Transaction)(success: => Unit)(
    implicit ctx: Context
  ) = new {
    private val before = ctx.txn
    ctx.txn = Literal.Empty
    success
    private val pass = ctx.txn
    ctx.txn = before
    ctx += branch(condition, pass, Literal.Empty)

    def Else(failure: => Unit): Unit = {
      ctx.txn = Literal.Empty
      failure
      val fail = ctx.txn
      ctx.txn = before
      ctx += branch(condition, pass, fail)
    }
  }

  /**
   * Repeatedly performs the specified block while the specified condition is satisfied.
   *
   * @param condition Condition to loop on.
   * @param block Loop body.
   * @param ctx Implicit transaction context.
   */
  def While(condition: Transaction)(block: => Unit)(
    implicit ctx: Context
  ): Unit = {
    val before = ctx.txn
    ctx.txn = Literal.Empty
    block
    val body = ctx.txn
    ctx.txn = before
    ctx += repeat(condition, body)
  }

  /**
   * Repeatedly performs the specified block over the specified interval, storing the loop index in
   * the specified variable before each iteration.
   *
   * @param variable Loop variable.
   * @param interval Loop interval.
   * @param block Loop body.
   * @param ctx Implicit transaction context.
   */
  def For(variable: Variable, interval: Interval)(block: => Unit)(
    implicit ctx: Context
  ): Unit = {
    ctx += store(variable.name, interval.start)

    val condition = interval match {
      case Interval(_, end, _, true)  => load(variable.name) <= end
      case Interval(_, end, _, false) => load(variable.name) <  end
    }

    val before = ctx.txn
    ctx.txn = Literal.Empty
    block
    ctx += store(variable.name, load(variable.name) + interval.step)
    val body = ctx.txn
    ctx.txn = before
    ctx += repeat(condition, body)
  }

  /**
   * Iterates over each key in an index, storing the current key in the specified variable before
   * each iteration. Automatically prefetches index keys.
   *
   * @param variable Loop variable.
   * @param in Index to iterate over.
   * @param block  Loop body.
   * @param ctx Implicit transaction context.
   */
  def Foreach(variable: Variable, in: Field)(block: => Unit)(
    implicit ctx: Context
  ): Unit = {
    val names = read(in.key ++ FieldDelimiter ++ "$addresses")

    // Prefetch all the addresses of the collection.
    ctx.$i = 0
    ctx.$addresses = names
    While (ctx.$i < length(ctx.$addresses)) {
      ctx.$j = ctx.$i + 1
      While (ctx.$addresses.charAt(ctx.$j) <> ArrayDelimiter) {
        ctx.$j = ctx.$j + 1
      }

      val prefix = ctx.$addresses.substring(0, ctx.$i)
      val suffix = ctx.$addresses.substring(ctx.$i)
      ctx.$addresses = prefix ++ in.key ++ FieldDelimiter ++ suffix
      ctx.$i = ctx.$j + length(in.key ++ FieldDelimiter) + 1
    }

    ctx += prefetch(ctx.$addresses)

    // Iterate over all the names of addresses.
    ctx.$i = 0
    While (ctx.$i < length(names)) {
      ctx.$j = ctx.$i + 1
      While (names.charAt(ctx.$j) <> ArrayDelimiter) {
        ctx.$j = ctx.$j + 1
      }

      // Load the index into the index variable and perform the block.
      ctx += store(variable.name, names.substring(ctx.$i, ctx.$j))
      block
      ctx.$i = ctx.$j + 1
    }
  }

  /**
   * Serializes an object and its various fields to json.
   *
   * @param obj Object to serialize.
   */
  def Stitch(obj: Object): Transaction = {
    // When working with loops, it is important to prefetch keys whenever possible.
    implicit val ctx = Context.empty
    ctx += prefetch(obj.$fields)
    ctx += prefetch(obj.$indices)
    ctx.$json = literal("{\"key\":\"") ++ obj.key ++ "\""

    // Serialize the various fields of the object.
    If(length(obj.$fields) > 0) {
      ctx.$i = 0

      While(ctx.$i < length(obj.$fields)) {
        ctx.$j = ctx.$i + 1
        While(obj.$fields.charAt(ctx.$j) <> ArrayDelimiter) {
          ctx.$j = ctx.$j + 1
        }

        val name = obj.$fields.substring(ctx.$i, ctx.$j)
        val value = read(obj.key ++ FieldDelimiter ++ name)
        ctx.$json = ctx.$json ++ ",\"" ++ name ++ "\":\"" ++ value ++ "\""
        ctx.$i = ctx.$j + 1
      }
    }

    // Serialize the various indices of the object.
    If (length(obj.$indices) > 0) {
      ctx.$i = 0

      While (ctx.$i < length(obj.$indices)) {
        ctx.$j = ctx.$i + 1
        While (obj.$indices.charAt(ctx.$j) <> ArrayDelimiter) {
          ctx.$j = ctx.$j + 1
        }

        val name = obj.$indices.substring(ctx.$i, ctx.$j)
        val index = read(obj.key ++ FieldDelimiter ++ name ++ FieldDelimiter ++ "$addresses")
        prefetch(index)
        ctx.$json = ctx.$json ++ ",\"" ++ name ++ "\":["
        ctx.$k = 0

        While(ctx.$k < length(index)) {
          ctx.$l = ctx.$k + 1
          While(!equal(index.charAt(ctx.$l), ArrayDelimiter)) {
            ctx.$l = ctx.$l + 1
          }

          val at = index.substring(ctx.$k, ctx.$l)
          val value = read(obj.key ++ FieldDelimiter ++ name ++ FieldDelimiter ++ at)
          ctx.$json = ctx.$json ++ "\"" ++ at ++ "\":\"" ++ value ++ "\","
          ctx.$k = ctx.$l + 1
        }

        ctx.$json = ctx.$json.substring(0, length(ctx.$json) - 1) ++ "]"
        ctx.$i = ctx.$j + 1
      }
    }

    // Place the serialized value into the context.
    ctx.$json = ctx.$json ++ "}"
    ctx += ctx.$json
    ctx.txn
  }

  /**
   * Returns the value of the specified transaction or transactions.
   *
   * @param first First transaction.
   * @param rest Optional other transactions.
   * @param ctx Implicit transaction context.
   */
  def Return(first: Transaction, rest: Transaction*)(
    implicit ctx: Context
  ): Unit =
    if (rest.isEmpty)
      ctx += first
    else
      ctx += concat("[", concat(
        rest.+:(first)
          .map(t => concat("\"", concat(t, "\"")))
          .reduceLeft((a, b) => a ++ "," ++ b),
        "]"
      ))

  /**
   * Converts the transaction into a read-only transaction; all writes are discarded.
   *
   * @param result Return value.
   * @param ctx Implicit transaction context.
   */
  def Rollback(result: Transaction = Literal.Empty)(
    implicit ctx: Context
  ): Unit = ctx += rollback(result)

}

object Language {

  /**
   * A loop interval.
   *
   * @param start Starting value.
   * @param end Ending value.
   * @param step Iteration step size.
   * @param inclusive Whether or not end value is inclusive.
   */
  case class Interval(
    start: Transaction,
    end: Transaction,
    step: Transaction,
    inclusive: Boolean
  ) {

    def by(s: Transaction): Interval = this.copy(step = s)

  }

}