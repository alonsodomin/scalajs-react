package japgolly.scalajs.react.vdom

import scala.scalajs.LinkingInfo.developmentMode
import scala.scalajs.js
import japgolly.scalajs.react.internal.JsUtil
import japgolly.scalajs.react.raw
import Builder.RawChild

/** Mutable target for immutable VDOM constituents to compose.
  */
trait Builder {
  val addAttr        : (String, js.Any) => Unit
  val addClassName   : js.Any           => Unit
  val addStyle       : (String, js.Any) => Unit
  val addStylesObject: js.Object        => Unit
  val appendChild    : RawChild         => Unit

  final def addStyles(j: js.Any): Unit = {
    // Hack because Attr.ValueType.Fn takes a js.Any => Unit.
    // Safe because Attr.Style declares that it needs a js.Object.
    val obj = j.asInstanceOf[js.Object]
    addStylesObject(obj)
  }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

object Builder {
  type RawChild = raw.ReactNodeList

  def setObjectKeyValue(o: js.Object, k: String, v: js.Any): Unit =
    o.asInstanceOf[js.Dynamic].updateDynamic(k)(v)

  def nonEmptyObject[O <: js.Object](o: O): js.UndefOr[O] =
    if (js.Object.keys(o).length == 0) js.undefined else o

  def nonEmptyJsArray[A](as: js.Array[A]): js.UndefOr[js.Array[A]] =
    if (as.length == 0) js.undefined else as

  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  /**
    * Raw JS values of:
    * - className
    * - props
    * - styles
    * - children
    *
    * Everything here is mutable.
    *
    * There are convenience methods to (mutably) add className and styles to props.
    */
  trait ToJs extends Builder {
    // Exposing vars here is acceptable because:
    // 1. The contents are all mutable anyway and a defensive-copy cost isn't worth it
    // 2. None of this is visible by default in the main public API
    // 3. Target audience is hackers doing hacky things, so more control is preferred

    var props    : js.Object          = new js.Object
    var styles   : js.Object          = new js.Object
    var children : js.Array[RawChild] = new js.Array[RawChild]()

    var nonEmptyClassName: js.UndefOr[js.Any            ] = js.undefined
    def nonEmptyProps    : js.UndefOr[js.Object         ] = nonEmptyObject(props)
    def nonEmptyStyles   : js.UndefOr[js.Object         ] = nonEmptyObject(styles)
    def nonEmptyChildren : js.UndefOr[js.Array[RawChild]] = nonEmptyJsArray(children)

    override val addAttr: (String, js.Any) => Unit =
      setObjectKeyValue(props, _, _)

    override val addClassName: js.Any => Unit =
      n => nonEmptyClassName = nonEmptyClassName.fold(n)(_ + " " + n)

    override val addStyle: (String, js.Any) => Unit =
      setObjectKeyValue(styles, _, _)

    override val addStylesObject: js.Object => Unit =
      o => for ((k, v) <- JsUtil.objectIterator(o)) addStyle(k, v)

    override val appendChild: RawChild => Unit =
      children.push(_)

    def addClassNameToProps(): Unit =
      nonEmptyClassName.foreach(setObjectKeyValue(props, "className", _))

    def addStyleToProps(): Unit =
      nonEmptyStyles.foreach(setObjectKeyValue(props, "style", _))

    def childrenAsVdomNodes: List[VdomNode] = {
      import Implicits._
      var i = children.length
      var nodes = List.empty[VdomNode]
      while (i > 0) {
        i -= 1
        nodes ::= children(i)
      }
      nodes
    }

    def nonEmptyChildrenAsVdomNodes: js.UndefOr[List[VdomNode]] =
      if (children.length == 0) js.undefined else childrenAsVdomNodes
  }

  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  class ToVdomElement extends ToJs {
    def render(tag: String): VdomElement = {
      addClassNameToProps()
      addStyleToProps()
      val e = ToRawReactElement.build(tag, props, children)
      VdomElement(e)
    }
  }

  object ToRawReactElement {
    type BuildFn = (String, js.Object, js.Array[RawChild]) => raw.ReactElement

    val build: BuildFn =
      if (developmentMode)

        // Development mode
        (tag, props, children) => {
          raw.React.createElement(tag, props, children: _*)
        }

      else {

        // Production mode
        // http://babeljs.io/blog/2015/03/31/5.0.0/#inline-elements

        // Logic here taken from:
        // https://github.com/babel/babel/blob/master/packages/babel-helpers/src/helpers.js
        // https://github.com/babel/babel/tree/master/packages/babel-plugin-transform-react-inline-elements/test/fixtures/inline-elements

        val REACT_ELEMENT_TYPE: js.Any =
          try
            js.Dynamic.global.Symbol.`for`("react.element")
          catch {
            case _: Throwable => 0xeac7
          }

        (tag, props, children) => {
          val ref = props.asInstanceOf[js.Dynamic].ref.asInstanceOf[js.UndefOr[js.Any]]
          if (ref.isDefined)
            raw.React.createElement(tag, props, children: _*)
          else {

            val key = props.asInstanceOf[js.Dynamic].key.asInstanceOf[js.UndefOr[js.Any]]

            val clen = children.length
            if (clen != 0) {
              val c = if (clen == 1) children(0) else children
              setObjectKeyValue(props, "children", c.asInstanceOf[js.Any])
            }

            val output =
              js.Dynamic.literal(
                `$$typeof` = REACT_ELEMENT_TYPE,
                `type`     = tag,
                key        = key.fold(null: js.Any)("" + _),
                ref        = null,
                props      = props,
                _owner     = null)
                .asInstanceOf[raw.ReactElement]

  //         org.scalajs.dom.console.log("VDOM: ", output)

            output
          }
        }
      }
  }
}
