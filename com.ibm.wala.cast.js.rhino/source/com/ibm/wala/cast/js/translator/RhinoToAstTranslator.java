/******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *****************************************************************************/
package com.ibm.wala.cast.js.translator;

import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Node;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.ArrayComprehension;
import org.mozilla.javascript.ast.ArrayComprehensionLoop;
import org.mozilla.javascript.ast.ArrayLiteral;
import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.Block;
import org.mozilla.javascript.ast.BreakStatement;
import org.mozilla.javascript.ast.CatchClause;
import org.mozilla.javascript.ast.Comment;
import org.mozilla.javascript.ast.ConditionalExpression;
import org.mozilla.javascript.ast.ContinueStatement;
import org.mozilla.javascript.ast.DoLoop;
import org.mozilla.javascript.ast.ElementGet;
import org.mozilla.javascript.ast.EmptyExpression;
import org.mozilla.javascript.ast.ErrorNode;
import org.mozilla.javascript.ast.ExpressionStatement;
import org.mozilla.javascript.ast.ForInLoop;
import org.mozilla.javascript.ast.ForLoop;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.IfStatement;
import org.mozilla.javascript.ast.InfixExpression;
import org.mozilla.javascript.ast.Jump;
import org.mozilla.javascript.ast.KeywordLiteral;
import org.mozilla.javascript.ast.Label;
import org.mozilla.javascript.ast.LabeledStatement;
import org.mozilla.javascript.ast.LetNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NewExpression;
import org.mozilla.javascript.ast.NumberLiteral;
import org.mozilla.javascript.ast.ObjectLiteral;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.RegExpLiteral;
import org.mozilla.javascript.ast.ReturnStatement;
import org.mozilla.javascript.ast.Scope;
import org.mozilla.javascript.ast.ScriptNode;
import org.mozilla.javascript.ast.StringLiteral;
import org.mozilla.javascript.ast.SwitchCase;
import org.mozilla.javascript.ast.SwitchStatement;
import org.mozilla.javascript.ast.Symbol;
import org.mozilla.javascript.ast.ThrowStatement;
import org.mozilla.javascript.ast.TryStatement;
import org.mozilla.javascript.ast.UnaryExpression;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.ast.VariableInitializer;
import org.mozilla.javascript.ast.WhileLoop;
import org.mozilla.javascript.ast.WithStatement;
import org.mozilla.javascript.ast.XmlDotQuery;
import org.mozilla.javascript.ast.XmlElemRef;
import org.mozilla.javascript.ast.XmlExpression;
import org.mozilla.javascript.ast.XmlFragment;
import org.mozilla.javascript.ast.XmlLiteral;
import org.mozilla.javascript.ast.XmlMemberGet;
import org.mozilla.javascript.ast.XmlPropRef;
import org.mozilla.javascript.ast.XmlRef;
import org.mozilla.javascript.ast.XmlString;
import org.mozilla.javascript.ast.Yield;
import org.mozilla.javascript.tools.ToolErrorReporter;

import com.ibm.wala.cast.ir.translator.TranslatorToCAst.DoLoopTranslator;
import com.ibm.wala.cast.js.html.MappedSourceModule;
import com.ibm.wala.cast.js.ipa.callgraph.JSSSAPropagationCallGraphBuilder;
import com.ibm.wala.cast.js.loader.JavaScriptLoader;
import com.ibm.wala.cast.js.translator.JavaScriptTranslatorToCAst;
import com.ibm.wala.cast.js.types.JavaScriptTypes;
import com.ibm.wala.cast.tree.CAst;
import com.ibm.wala.cast.tree.CAstControlFlowMap;
import com.ibm.wala.cast.tree.CAstEntity;
import com.ibm.wala.cast.tree.CAstNode;
import com.ibm.wala.cast.tree.CAstNodeTypeMap;
import com.ibm.wala.cast.tree.CAstQualifier;
import com.ibm.wala.cast.tree.CAstSourcePositionMap;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.tree.CAstType;
import com.ibm.wala.cast.tree.impl.CAstOperator;
import com.ibm.wala.cast.tree.impl.CAstSymbolImpl;
import com.ibm.wala.cast.tree.impl.LineNumberPosition;
import com.ibm.wala.classLoader.SourceModule;
import com.ibm.wala.util.collections.EmptyIterator;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.debug.Assertions;

public class RhinoToAstTranslator {

  /**
   * a dummy name to use for standard function calls, only used to distinguish
   * them from constructor calls
   */
  public static final String STANDARD_CALL_FN_NAME = "do";

  /**
   * name used for constructor calls, used to distinguish them from standard
   * function calls
   */
  public static final String CTOR_CALL_FN_NAME = "ctor";

  private final boolean DEBUG = false;

  /**
   * shared interface for all objects storing contextual information during the
   * Rhino AST traversal
   * 
   */
  private interface WalkContext extends JavaScriptTranslatorToCAst.WalkContext<WalkContext, Node> {

  }

  /**
   * default implementation of WalkContext; methods do nothing / return null
   * 
   */
  private static class RootContext extends JavaScriptTranslatorToCAst.RootContext<WalkContext, Node> implements WalkContext {

  }

  /**
   * context used for function / script declarations
   */
  private static class FunctionContext extends JavaScriptTranslatorToCAst.FunctionContext<WalkContext,Node> implements WalkContext {
    FunctionContext(WalkContext parent, Node s) {
		super(parent, s);
	}

   }

  /**
   * context used for top-level script declarations
   */
  private static class ScriptContext extends FunctionContext {
    private final String script;

    ScriptContext(WalkContext parent, ScriptNode s, String script) {
      super(parent, s);
      this.script = script;
    }

    @Override
    public String script() {
      return script;
    }
  }

  private static class BaseCollectingContext extends JavaScriptTranslatorToCAst.BaseCollectingContext<WalkContext, Node> implements WalkContext {

	BaseCollectingContext(WalkContext parent, Node initialBaseFor,
			String baseVar) {
		super(parent, initialBaseFor, baseVar);
	}
  
  }

  private static class BreakContext extends JavaScriptTranslatorToCAst.BreakContext<WalkContext, Node> implements WalkContext {

    BreakContext(WalkContext parent, Node breakTo, String label) {
      super(parent, breakTo, label);
    }

  }

  private static class LoopContext extends JavaScriptTranslatorToCAst.LoopContext<WalkContext, Node> implements WalkContext {

	LoopContext(WalkContext parent, Node breakTo, Node continueTo, String label) {
		super(parent, breakTo, continueTo, label);
	}

  }

  private static class TryCatchContext extends JavaScriptTranslatorToCAst.TryCatchContext<WalkContext, Node> implements WalkContext {

	TryCatchContext(WalkContext parent, CAstNode catchNode) {
		super(parent, catchNode);
	}

  }

  private CAstNode translateOpcode(int nodeType) {
    switch (nodeType) {
    case Token.POS:
    case Token.ADD:
    case Token.ASSIGN_ADD:
      return CAstOperator.OP_ADD;
    case Token.DIV:
    case Token.ASSIGN_DIV:
      return CAstOperator.OP_DIV;
    case Token.ASSIGN_LSH:
    case Token.LSH:
      return CAstOperator.OP_LSH;
    case Token.MOD:
    case Token.ASSIGN_MOD:
      return CAstOperator.OP_MOD;
    case Token.MUL:
    case Token.ASSIGN_MUL:
      return CAstOperator.OP_MUL;
    case Token.RSH:
    case Token.ASSIGN_RSH:
      return CAstOperator.OP_RSH;
    case Token.SUB:
    case Token.NEG:
    case Token.ASSIGN_SUB:
      return CAstOperator.OP_SUB;
    case Token.URSH:
    case Token.ASSIGN_URSH:
      return CAstOperator.OP_URSH;
    case Token.BITAND:
    case Token.ASSIGN_BITAND:
      return CAstOperator.OP_BIT_AND;
    case Token.BITOR:
    case Token.ASSIGN_BITOR:
      return CAstOperator.OP_BIT_OR;
    case Token.BITXOR:
    case Token.ASSIGN_BITXOR:
      return CAstOperator.OP_BIT_XOR;

    case Token.EQ:
      return CAstOperator.OP_EQ;
    case Token.SHEQ:
      return CAstOperator.OP_STRICT_EQ;
    case Token.IFEQ:
      return CAstOperator.OP_EQ;
    case Token.GE:
      return CAstOperator.OP_GE;
    case Token.GT:
      return CAstOperator.OP_GT;
    case Token.LE:
      return CAstOperator.OP_LE;
    case Token.LT:
      return CAstOperator.OP_LT;
    case Token.NE:
      return CAstOperator.OP_NE;
    case Token.SHNE:
      return CAstOperator.OP_STRICT_NE;
    case Token.IFNE:
      return CAstOperator.OP_NE;

    case Token.BITNOT:
      return CAstOperator.OP_BITNOT;
    case Token.NOT:
      return CAstOperator.OP_NOT;

    default:
      Assertions.UNREACHABLE();
      return null;
    }
  }

  private CAstNode makeBuiltinNew(String typeName) {
    return Ast.makeNode(CAstNode.NEW, Ast.makeConstant(typeName));
  }

  private CAstNode handleNew(WalkContext context, String globalName, CAstNode arguments[]) {
    return handleNew(context, readName(context, globalName), arguments);
  }

  private CAstNode handleNew(WalkContext context, CAstNode value, CAstNode arguments[]) {
    return makeCtorCall(value, arguments, context);
  }

  private boolean isPrologueScript(WalkContext context) {
    return JavaScriptLoader.bootstrapFileNames.contains(context.script());
  }

  private Node getCallTarget(FunctionCall n) {
	  return n.getTarget();
  }
  /**
   * is n a call to "primitive" within our synthetic modeling code?
   */
  private boolean isPrimitiveCall(WalkContext context, FunctionCall n) {
    return isPrologueScript(context) && n.getType() == Token.CALL && getCallTarget(n).getType() == Token.NAME
        && getCallTarget(n).getString().equals("primitive");
  }

  private Node getNewTarget(NewExpression n) {
	  return n.getTarget();
  }
  
  private boolean isPrimitiveCreation(WalkContext context, NewExpression n) {
	  Node target = getNewTarget(n);
    return isPrologueScript(context) && n.getType() == Token.NEW && target.getType() == Token.NAME
        && target.getString().equals("Primitives");
  }

  private CAstNode makeCall(CAstNode fun, CAstNode thisptr, CAstNode args[], WalkContext context) {
    return makeCall(fun, thisptr, args, context, STANDARD_CALL_FN_NAME);
  }

  private CAstNode makeCtorCall(CAstNode thisptr, CAstNode args[], WalkContext context) {
    return makeCall(thisptr, null, args, context, CTOR_CALL_FN_NAME);
  }

  private CAstNode makeCall(CAstNode fun, CAstNode thisptr, CAstNode args[], WalkContext context, String callee) {
    int children = (args == null)? 0 : args.length;

    // children of CAst CALL node are the expression that evaluates to the
    // function, followed by a name (either STANDARD_CALL_FN_NAME or
    // CTOR_CALL_FN_NAME), followed by the actual
    // parameters
    int nargs = (thisptr == null) ? children + 2 : children + 3;
    int i = 0;
    CAstNode arguments[] = new CAstNode[nargs];
    arguments[i++] = fun;
    assert callee.equals(STANDARD_CALL_FN_NAME) || callee.equals(CTOR_CALL_FN_NAME);
    arguments[i++] = Ast.makeConstant(callee);
    if (thisptr != null)
      arguments[i++] = thisptr;
    if (args != null) {
    	for (CAstNode arg : args) {
    		arguments[i++] = arg;
    	}
    }
    
    CAstNode call = Ast.makeNode(CAstNode.CALL, arguments);

    context.cfg().map(call, call);
    if (context.getCatchTarget() != null) {
      context.cfg().add(call, context.getCatchTarget(), null);
    }

    return call;
  }
  
  /**
   * Used to represent a script or function in the CAst; see walkEntity().
   * 
   */
  private static class ScriptOrFnEntity implements CAstEntity {
    private final String[] arguments;

    private final String name;

    private final int kind;

    private final Map<CAstNode, Collection<CAstEntity>> subs;

    private final CAstNode ast;

    private final CAstControlFlowMap map;

    private final CAstSourcePositionMap pos;
    
    private final Position entityPosition;
    
    private ScriptOrFnEntity(AstNode n, Map<CAstNode, Collection<CAstEntity>> subs, CAstNode ast, CAstControlFlowMap map, CAstSourcePositionMap pos, String name) {
      this.name = name;
      this.entityPosition = pos.getPosition(ast);

      if (n instanceof FunctionNode) {
        FunctionNode f = (FunctionNode) n;
        f.flattenSymbolTable(false);
        int i = 0;
        arguments = new String[f.getParamCount() + 2];
        arguments[i++] = name;
        arguments[i++] = "this";
        for (int j = 0; j < f.getParamCount(); j++) {
          arguments[i++] = f.getParamOrVarName(j);
        }
      } else {
        arguments = new String[0];
      }
      kind = (n instanceof FunctionNode) ? CAstEntity.FUNCTION_ENTITY : CAstEntity.SCRIPT_ENTITY;
      this.subs = subs;
      this.ast = ast;
      this.map = map;
      this.pos = pos;
    }

    @Override
    public String toString() {
      return "<JS function " + getName() + ">";
    }

    public String getName() {
      return name;
    }

    public String getSignature() {
      Assertions.UNREACHABLE();
      return null;
    }

    public int getKind() {
      return kind;
    }

    public String[] getArgumentNames() {
      return arguments;
    }

    public CAstNode[] getArgumentDefaults() {
      return new CAstNode[0];
    }

    public int getArgumentCount() {
      return arguments.length;
    }

    public Map<CAstNode, Collection<CAstEntity>> getAllScopedEntities() {
      return Collections.unmodifiableMap(subs);
    }

    public Iterator<CAstEntity> getScopedEntities(CAstNode construct) {
      if (subs.containsKey(construct))
        return subs.get(construct).iterator();
      else
        return EmptyIterator.instance();
    }

    public CAstNode getAST() {
      return ast;
    }

    public CAstControlFlowMap getControlFlow() {
      return map;
    }

    public CAstSourcePositionMap getSourceMap() {
      return pos;
    }

    public CAstSourcePositionMap.Position getPosition() {
      return entityPosition;
    }

    public CAstNodeTypeMap getNodeTypeMap() {
      return null;
    }

    public Collection<CAstQualifier> getQualifiers() {
      Assertions.UNREACHABLE("JuliansUnnamedCAstEntity$2.getQualifiers()");
      return null;
    }

    public CAstType getType() {
      Assertions.UNREACHABLE("JuliansUnnamedCAstEntity$2.getType()");
      return null;
    }
  }

  private CAstEntity walkEntity(final AstNode n, List<CAstNode> body, String name, WalkContext child) {
    CAstNode[] stmts = body.toArray(new CAstNode[body.size()]);

    // add variable / constant / function declarations, if any
    if (!child.getNameDecls().isEmpty()) {
      // new first statement will be a block declaring all names.
      CAstNode[] newStmts = new CAstNode[stmts.length + 1];

      newStmts[0] = Ast.makeNode(CAstNode.BLOCK_STMT, child.getNameDecls().toArray(new CAstNode[child.getNameDecls().size()]));

      System.arraycopy(stmts, 0, newStmts, 1, stmts.length);

      stmts = newStmts;
    }

    final CAstNode ast = noteSourcePosition(child, Ast.makeNode(CAstNode.BLOCK_STMT, stmts), n);
    final CAstControlFlowMap map = child.cfg();
    final CAstSourcePositionMap pos = child.pos();

    // not sure if we need this copy --MS
    final Map<CAstNode, Collection<CAstEntity>> subs = HashMapFactory.make(child.getScopedEntities());
    
    return new ScriptOrFnEntity(n, subs, ast, map, pos, name);
  }
  
  private Position makePosition(AstNode n) {
    URL url = sourceModule.getURL();
    int line = n.getLineno(); 
    if (sourceModule instanceof MappedSourceModule) {
      return ((MappedSourceModule) sourceModule).getMapping().getAssociatedFileAndLine(line);
    } else {
    	return new LineNumberPosition(url, url, line);
    }
  }

  private void pushSourcePosition(WalkContext context, CAstNode n, Position p) {
	  if (context.pos().getPosition(n) == null) {
	      context.pos().setPosition(n, p);
	      for(int i = 0; i < n.getChildCount(); i++) {
	    	  pushSourcePosition(context, n.getChild(i), p);
	      }
	  }
  }
  private CAstNode noteSourcePosition(WalkContext context, CAstNode n, AstNode p) {
    if (p.getLineno() != -1 && context.pos().getPosition(n) == null) {
      pushSourcePosition(context, n, makePosition(p));
    }
    return n;
  }
  
  private CAstNode readName(WalkContext context, String name) {
    CAstNode cn = makeVarRef(name);
    context.cfg().map(cn, cn);
    CAstNode target = context.getCatchTarget();
    if (target != null) {
      context.cfg().add(cn, target, JavaScriptTypes.ReferenceError);
    } else {
      context.cfg().add(cn, CAstControlFlowMap.EXCEPTION_TO_EXIT, JavaScriptTypes.ReferenceError);
    }
    return cn;
  }
  
  private List<Label> getLabels(AstNode node) {
	  if (node instanceof LabeledStatement || ((node = node.getParent()) instanceof LabeledStatement)) {
		  return ((LabeledStatement)node).getLabels();
	  } else {
		  return null;
	  }
  }
  private AstNode makeEmptyLabelStmt(String label) {
	  Label l = new Label();
	  l.setName(label);
	  LabeledStatement st = new LabeledStatement();
	  st.addLabel(l);
	  ExpressionStatement ex = new ExpressionStatement();
	  ex.setExpression(new EmptyExpression());
	  st.setStatement(ex);
	  return st;
  }
  
  private WalkContext makeLoopContext(AstNode node, WalkContext arg,
			AstNode breakStmt, AstNode contStmt) {
	  WalkContext loopContext = arg;
	  List<Label> labels = getLabels(node);
	  if (labels == null) {
		  loopContext = new LoopContext(loopContext, breakStmt, contStmt, null);
	  } else {
		  for(Label l : labels) {
			  loopContext = new LoopContext(loopContext, breakStmt, contStmt, l.getName());				
		  }
	  }
	  return loopContext;
  }

  private WalkContext makeBreakContext(AstNode node, WalkContext arg,
      AstNode breakStmt) {
    WalkContext loopContext = arg;
    List<Label> labels = getLabels(node);
    if (labels == null) {
      loopContext = new BreakContext(loopContext, breakStmt, null);
    } else {
      for(Label l : labels) {
        loopContext = new BreakContext(loopContext, breakStmt, l.getName());       
      }
    }
    return loopContext;
  }

  private class TranslatingVisitor extends TypedNodeVisitor<CAstNode,WalkContext> {

	@Override
	public CAstNode visit(AstNode node, WalkContext arg) {
		CAstNode ast = super.visit(node, arg);
		return noteSourcePosition(arg, ast, node);
	}

	@Override
	public CAstNode visitArrayComprehension(ArrayComprehension node,
			WalkContext arg) {
		// TODO Auto-generated method stub
		assert false;
		return null;
	}

	@Override
	public CAstNode visitArrayComprehensionLoop(ArrayComprehensionLoop node,
			WalkContext arg) {
		// TODO Auto-generated method stub
		assert false;
		return null;
	}

	@Override
	public CAstNode visitArrayLiteral(ArrayLiteral node, WalkContext arg) {
		int index = 0;
		List<CAstNode> eltNodes = new ArrayList<CAstNode>(2 * node.getElements().size());
    	eltNodes.add(((isPrologueScript(arg)) ? makeBuiltinNew("Array") : handleNew(arg, "Array", null)));
		for(AstNode elt : node.getElements()) {
			if (elt instanceof EmptyExpression) {
				index++;
			} else {
				eltNodes.add(Ast.makeConstant(index++));
				eltNodes.add(visit(elt, arg));
			}
		}
		
		return Ast.makeNode(CAstNode.OBJECT_LITERAL, eltNodes.toArray(new CAstNode[eltNodes.size()]));
	}

	@Override
	public CAstNode visitAssignment(Assignment node, WalkContext arg) {
		if (node.getType() == Token.ASSIGN) {
			return Ast.makeNode(CAstNode.ASSIGN, visit(node.getLeft(), arg), visit(node.getRight(), arg));
		} else {
			return Ast.makeNode(CAstNode.ASSIGN_POST_OP, visit(node.getLeft(), arg), visit(node.getRight(), arg), translateOpcode(node.getOperator()));			
		}
	}

	@Override
	public CAstNode visitAstRoot(AstRoot node, WalkContext arg) {
		int i = 0;
		CAstNode[] children = new CAstNode[ node.getStatements().size() ];
		for(AstNode n : node.getStatements()) {
			children[i++] = this.visit(n, arg);
		}
		return Ast.makeNode(CAstNode.BLOCK_STMT, children);
	}

	@Override
	public CAstNode visitBlock(Block node, WalkContext arg) {
		List<CAstNode> nodes = new ArrayList<CAstNode>();
		for(Node child : node) {
			nodes.add(visit((AstNode)child, arg));
		}
		if (nodes.isEmpty()) {
			return Ast.makeNode(CAstNode.EMPTY);
		} else {
			return Ast.makeNode(CAstNode.BLOCK_STMT, nodes.toArray(new CAstNode[ nodes.size() ]));
		}
	}

	@Override
	public CAstNode visitBreakStatement(BreakStatement node, WalkContext arg) {
		CAstNode breakStmt;
		Node target;
		if (node.getBreakLabel() != null) {
			breakStmt = Ast.makeNode(CAstNode.GOTO, Ast.makeConstant(node.getBreakLabel().getIdentifier()));
			target = arg.getBreakFor(node.getBreakLabel().getIdentifier());
		} else {
			breakStmt = Ast.makeNode(CAstNode.GOTO);
			target = arg.getBreakFor(null);
		}

		arg.cfg().map(node, breakStmt);
		arg.cfg().add(node, target, null);
		
		return breakStmt;
	}

	@Override
	public CAstNode visitCatchClause(CatchClause node, WalkContext arg) {
		return visit(node.getBody(), arg);
	}

	@Override
	public CAstNode visitComment(Comment node, WalkContext arg) {
		return Ast.makeNode(CAstNode.EMPTY);
	}

	@Override
	public CAstNode visitConditionalExpression(ConditionalExpression node,
			WalkContext arg) {
		return Ast.makeNode(CAstNode.IF_EXPR, 
				visit(node.getTestExpression(), arg), 
				visit(node.getTrueExpression(), arg), 
				visit(node.getFalseExpression(), arg));
	}

	@Override
	public CAstNode visitContinueStatement(ContinueStatement node,
			WalkContext arg) {
		CAstNode continueStmt;
		if (node.getLabel() != null) {
			continueStmt = Ast.makeNode(CAstNode.GOTO, Ast.makeConstant(node.getLabel().getIdentifier()));
		} else {
			continueStmt = Ast.makeNode(CAstNode.GOTO);
		}
		arg.cfg().map(node, continueStmt);

		Node target = node.getTarget();
		arg.cfg().add(node, target, null);
		
		return continueStmt;
	}

	@Override
	public CAstNode visitDoLoop(DoLoop node, WalkContext arg) {
		CAstNode loopTest = visit(node.getCondition(), arg);
				
		AstNode breakStmt = makeEmptyLabelStmt("breakLabel");
		CAstNode breakLabel = visit(breakStmt, arg);
		AstNode contStmt = makeEmptyLabelStmt("contLabel");
		CAstNode contLabel = visit(contStmt, arg);

		WalkContext loopContext = makeLoopContext(node, arg, breakStmt, contStmt);

		CAstNode loopBody = visit(node.getBody(), loopContext);

		CAstNode loop = doLoopTranslator.translateDoLoop(loopTest, loopBody, contLabel, breakLabel, arg);
		arg.cfg().map(node, loop);
		return loop;
	}

	private CAstNode visitObjectRead(AstNode n, AstNode objAst, CAstNode elt, WalkContext context) {
		CAstNode obj = visit(objAst, context);
		String baseVar = context.getBaseVarIfRelevant(n);
		
		CAstNode get, result;
		if (baseVar != null) {
		  result = Ast.makeNode(CAstNode.BLOCK_EXPR, 
		                          Ast.makeNode(CAstNode.ASSIGN, Ast.makeNode(CAstNode.VAR, Ast.makeConstant(baseVar)), obj),
		                          get = Ast.makeNode(CAstNode.OBJECT_REF, Ast.makeNode(CAstNode.VAR, Ast.makeConstant(baseVar)), elt));
		} else {
		  result = get = Ast.makeNode(CAstNode.OBJECT_REF, obj, elt);
		}

		if (context.getCatchTarget() != null) {
		  context.cfg().map(get, get);
		  context.cfg().add(get, context.getCatchTarget(), JavaScriptTypes.TypeError);
		}

		return result;		
	}
	
	@Override
	public CAstNode visitElementGet(ElementGet node, WalkContext arg) {
		return visitObjectRead(node, node.getTarget(), visit(node.getElement(), arg), arg);
	}

	@Override
	public CAstNode visitEmptyExpression(EmptyExpression node, WalkContext arg) {
		return Ast.makeNode(CAstNode.EMPTY);
	}

	@Override
	public CAstNode visitErrorNode(ErrorNode node, WalkContext arg) {
		assert false;
		return null;
	}

	@Override
	public CAstNode visitExpressionStatement(ExpressionStatement node,
			WalkContext arg) {
		return visit(node.getExpression(), arg);
	}

	@Override
	public CAstNode visitForInLoop(ForInLoop node, WalkContext arg) {
		String tempName = "for in loop temp";
		
		// set up 		
		AstNode object = node.getIteratedObject();
		CAstNode loopTemp =
			Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl(tempName)),
				  visit(object, arg));

		CAstNode initNode;
		AstNode var = node.getIterator();
		assert var instanceof Name || var instanceof VariableDeclaration || var instanceof LetNode : var.getClass()  + " " + var;
		if (var instanceof Name) {
      initNode = 
        Ast.makeNode(CAstNode.ASSIGN, 
          Ast.makeNode(CAstNode.VAR, Ast.makeConstant(((Name)var).getString())),
          Ast.makeNode(CAstNode.EACH_ELEMENT_GET, Ast.makeNode(CAstNode.VAR, Ast.makeConstant(tempName))));
		  
		} else {
		  boolean isLet;
		  VariableDeclaration decl;
		  if (var instanceof LetNode) {
		    isLet = true;
		    decl = ((LetNode)var).getVariables();
		  } else {
		    isLet = false;
		    decl = (VariableDeclaration)var;
		  }
		  assert decl.getVariables().size() == 1;
		  VariableInitializer init = decl.getVariables().iterator().next();
		  if (isLet) {
		    initNode = 
		      Ast.makeNode(CAstNode.DECL_STMT, 
		          Ast.makeConstant(new CAstSymbolImpl(init.getTarget().getString())),
		          Ast.makeNode(CAstNode.EACH_ELEMENT_GET, Ast.makeNode(CAstNode.VAR, Ast.makeConstant(tempName))));

		  } else {
		    arg.addNameDecl(
		        Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl(init.getTarget().getString())),
		            readName(arg, "$$undefined")));

		    initNode = 
		      Ast.makeNode(CAstNode.ASSIGN, 
		          Ast.makeNode(CAstNode.VAR, Ast.makeConstant(init.getTarget().getString())),
		          Ast.makeNode(CAstNode.EACH_ELEMENT_GET, Ast.makeNode(CAstNode.VAR, Ast.makeConstant(tempName))));
		  }
		}
		
		// body
		AstNode breakStmt = makeEmptyLabelStmt("breakLabel");
		CAstNode breakLabel = visit(breakStmt, arg);
		AstNode contStmt = makeEmptyLabelStmt("contLabel");
		CAstNode contLabel = visit(contStmt, arg);
		WalkContext loopContext = makeLoopContext(node, arg, breakStmt, contStmt);
		CAstNode body = Ast.makeNode(CAstNode.BLOCK_STMT,
				initNode,
				visit(node.getBody(), loopContext),
				contLabel);
		
		CAstNode loop = Ast.makeNode(CAstNode.LOCAL_SCOPE,
				Ast.makeNode(CAstNode.BLOCK_STMT,
						loopTemp,
						Ast.makeNode(CAstNode.LOOP,
								Ast.makeNode(CAstNode.EACH_ELEMENT_HAS_NEXT, 
										Ast.makeNode(CAstNode.VAR, Ast.makeConstant(tempName))),
								body),
						breakLabel));
		arg.cfg().map(node, loop);
		return loop;
	}

	@Override
	public CAstNode visitForLoop(ForLoop node, WalkContext arg) {
		AstNode breakStmt = makeEmptyLabelStmt("breakLabel");
		CAstNode breakLabel = visit(breakStmt, arg);
		AstNode contStmt = makeEmptyLabelStmt("contLabel");
		CAstNode contLabel = visit(contStmt, arg);

		WalkContext loopContext = makeLoopContext(node, arg, breakStmt, contStmt);

		CAstNode loop = Ast.makeNode(CAstNode.BLOCK_STMT, 
		   visit(node.getInitializer(), arg),
		   Ast.makeNode(CAstNode.LOOP, 
				   visit(node.getCondition(), arg), 
				   Ast.makeNode(CAstNode.BLOCK_STMT,
				     visit(node.getBody(), loopContext),
				     contLabel,
				     visit(node.getIncrement(), arg))),
		   breakLabel);
		arg.cfg().map(node, loop);
		return loop;
	}

	private CAstNode[] gatherCallArguments(Node call, WalkContext context) {
		List<AstNode> nodes = ((FunctionCall)call).getArguments();
		CAstNode[] args = new CAstNode[ nodes.size() ];
		for(int i = 0; i < nodes.size(); i++) {
			args[i] = visit(nodes.get(i), context);
		}
			  
		return args;
	}

	private static final String baseVarName = "$$-base-$$";
	
	@Override
	public CAstNode visitFunctionCall(FunctionCall n, WalkContext context) {
		if (!isPrimitiveCall(context, n)) {
			CAstNode base = Ast.makeNode(CAstNode.VAR, Ast.makeConstant(baseVarName));
			AstNode callee = n.getTarget();
			WalkContext child = new BaseCollectingContext(context, callee, baseVarName);
			CAstNode fun = visit(callee, child);

			// the first actual parameter appearing within the parentheses of the
			// call (i.e., possibly excluding the 'this' parameter)
			CAstNode[] args = gatherCallArguments(n, context);
			if (child.foundBase(callee))
				return Ast.makeNode(
						CAstNode.LOCAL_SCOPE,
						Ast.makeNode(CAstNode.BLOCK_EXPR,
								Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl(baseVarName)), Ast.makeConstant(null)),
								makeCall(fun, base, args, context)));
			else
				return makeCall(fun, makeVarRef(JSSSAPropagationCallGraphBuilder.GLOBAL_OBJ_VAR_NAME), args, context);
		} else {
			return Ast.makeNode(CAstNode.PRIMITIVE, gatherCallArguments(n, context));
		}
	}

	@Override
	public CAstNode visitFunctionNode(FunctionNode fn, WalkContext context) {
		WalkContext child = new FunctionContext(context, fn);
	    List<CAstNode> body = new ArrayList<CAstNode>();
	    body.add(visit(fn.getBody(), child));

	    String name;
	    Name x = fn.getFunctionName();
	    if (x == null || x.getIdentifier() == null || "".equals(x.getIdentifier())) {
	    	name = scriptName + "_anonymous_" + anonymousCounter++;
	    } else {
	    	name = fn.getFunctionName().getIdentifier();
	    }

	    if(DEBUG)
	      System.err.println(name + "\n" + body);

		CAstEntity fne = walkEntity(fn, body, name, child);

		if(DEBUG)
		  System.err.println(fne.getName());
	      
		if (fn.getFunctionType() == FunctionNode.FUNCTION_EXPRESSION) {
			CAstNode fun = Ast.makeNode(CAstNode.FUNCTION_EXPR, Ast.makeConstant(fne));

	        context.addScopedEntity(fun, fne);

	        return fun;

		} else {
	        context.addNameDecl(Ast.makeNode(CAstNode.FUNCTION_STMT, Ast.makeConstant(fne)));

	        context.addScopedEntity(null, fne);

	        return Ast.makeNode(CAstNode.EMPTY);
		}
	}

	@Override
	public CAstNode visitIfStatement(IfStatement node, WalkContext arg) {
		if (node.getElsePart() != null) {
			return Ast.makeNode(CAstNode.IF_STMT, visit(node.getCondition(), arg), visit(node.getThenPart(), arg), visit(node.getElsePart(), arg));		
		} else {
			return Ast.makeNode(CAstNode.IF_STMT, visit(node.getCondition(), arg), visit(node.getThenPart(), arg));				
		}
	}
	
	@Override
	public CAstNode visitInfixExpression(InfixExpression node, WalkContext arg) {
		if (node.getType() == Token.OR) {
		      CAstNode l = visit(node.getLeft(), arg);
		      CAstNode r = visit(node.getRight(), arg);
		      return Ast.makeNode(CAstNode.LOCAL_SCOPE,
		      		  Ast.makeNode(CAstNode.BLOCK_EXPR,
		      		    Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl("or temp")), l),
		    		    Ast.makeNode(CAstNode.IF_EXPR,
		    				  Ast.makeNode(CAstNode.VAR, Ast.makeConstant("or temp")),
		    				  Ast.makeNode(CAstNode.VAR, Ast.makeConstant("or temp")),
		    				  r)));
		} else if (node.getType() == Token.AND) {
			      CAstNode l = visit(node.getLeft(), arg);
			      CAstNode r = visit(node.getRight(), arg);
			      return Ast.makeNode(CAstNode.LOCAL_SCOPE,
			    		  Ast.makeNode(CAstNode.BLOCK_EXPR,
			    		    Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl("and temp")), l),
			    		    Ast.makeNode(CAstNode.IF_EXPR,
			    				  Ast.makeNode(CAstNode.VAR, Ast.makeConstant("and temp")),
			    				  r,
			    				  Ast.makeNode(CAstNode.VAR, Ast.makeConstant("and temp")))));
		} else if (node.getType() == Token.COMMA) {
			return Ast.makeNode(CAstNode.BLOCK_EXPR,
					visit(node.getLeft(), arg),
					visit(node.getRight(), arg));
			
		} else if (node.getType() == Token.IN) {
			AstNode value = node.getLeft();
			AstNode property = node.getRight();
			return Ast.makeNode(CAstNode.IS_DEFINED_EXPR, visit(value, arg), visit(property, arg));
			
		} else if (node.getType() == Token.INSTANCEOF) {
			AstNode value = node.getLeft();
			AstNode type = node.getRight();
			return Ast.makeNode(CAstNode.INSTANCEOF, visit(value, arg), visit(type, arg));

		} else {
		    	return Ast.makeNode(CAstNode.BINARY_EXPR, 
		    			translateOpcode(node.getOperator()), 
		    			visit(node.getLeft(), arg), 
		    			visit(node.getRight(), arg));
		}
	}

	@Override
	public CAstNode visitJump(Jump node, WalkContext arg) {
		throw new InternalError("should not see jump nodes");
	}

	@Override
	public CAstNode visitKeywordLiteral(KeywordLiteral node, WalkContext arg) {
		switch (node.getType()) {
		case Token.THIS: {
		  if (arg.top() instanceof ScriptNode && !(arg.top() instanceof FunctionNode)) {
		    return makeVarRef(JSSSAPropagationCallGraphBuilder.GLOBAL_OBJ_VAR_NAME);
		  } else {
		    return Ast.makeNode(CAstNode.VAR, Ast.makeConstant("this"));
		  }
		}
		case Token.TRUE: {
			return Ast.makeConstant(true);
		}
		case Token.FALSE: {
			return Ast.makeConstant(false);
		}
		case Token.NULL: {
			return Ast.makeConstant(null);
		}
		}
		throw new RuntimeException("unexpected keyword literal " + node + " (" + node.getType() +")");
	}

	@Override
	public CAstNode visitLabel(Label node, WalkContext arg) {
		String label = node.getName();
		return Ast.makeConstant(label);
	}

	@Override
	public CAstNode visitLabeledStatement(LabeledStatement node, WalkContext arg) {
		CAstNode result = visit(node.getStatement(), arg);

		AstNode prev = node;
		for(Label label : node.getLabels()) {
			result = Ast.makeNode(CAstNode.LABEL_STMT, visit(label, arg), result);
			arg.cfg().map(prev, result);
			prev = label;
		}
		
		return result;
	}

	@Override
	public CAstNode visitLetNode(LetNode node, WalkContext arg) {
		VariableDeclaration decl = node.getVariables();
		int i = 0;
		CAstNode[] stmts = new CAstNode[ decl.getVariables().size() + 1 ];
		for(VariableInitializer init : decl.getVariables()) {
			stmts[i++] = 
				Ast.makeNode(CAstNode.DECL_STMT, 
					Ast.makeConstant(new CAstSymbolImpl(init.getTarget().getString())),
					visit(init, arg));
		}
		stmts[i++] = visit(node.getBody(), arg);
		return Ast.makeNode(CAstNode.LOCAL_SCOPE, stmts);
	}

	@Override
	public CAstNode visitName(Name n, WalkContext context) {
	      return readName(context, n.getString());
	}

	@Override
	public CAstNode visitNewExpression(NewExpression n, WalkContext context) {
	  if (isPrimitiveCreation(context, n)) {
		  return makeBuiltinNew(getNewTarget(n).getString());
	  } else {
		  AstNode receiver = n.getTarget();
		  return handleNew(context, visit(receiver, context), gatherCallArguments(n, context));
	  }
	}

	@Override
	public CAstNode visitNumberLiteral(NumberLiteral node, WalkContext arg) {
		return Ast.makeConstant(node.getDouble());
	}

	@Override
	public CAstNode visitObjectLiteral(ObjectLiteral n, WalkContext context) {
    	List<ObjectProperty> props = n.getElements();
    	CAstNode[] args = new CAstNode[props.size() * 2 + 1];
    	int i = 0;
    	args[i++] = ((isPrologueScript(context)) ? makeBuiltinNew("Object") : handleNew(context, "Object", null));
    	for(ObjectProperty prop : props) {
    		AstNode label = prop.getLeft();
    		args[i++] = 
    			(label instanceof Name)? Ast.makeConstant(((Name)prop.getLeft()).getString()):
    			visit(label, context);
    		args[i++] = visit(prop, context);
    	}
        return Ast.makeNode(CAstNode.OBJECT_LITERAL, args);
	}

	@Override
	public CAstNode visitObjectProperty(ObjectProperty node, WalkContext context) {
		return visit(node.getRight(), context);
	}

	@Override
	public CAstNode visitParenthesizedExpression(ParenthesizedExpression node,
			WalkContext arg) {
		return visit(node.getExpression(), arg);
	}

	@Override
	public CAstNode visitPropertyGet(PropertyGet node, WalkContext arg) {
		CAstNode elt = Ast.makeConstant(node.getProperty().getString());
		return visitObjectRead(node, node.getTarget(), elt, arg);
	}

	@Override
	public CAstNode visitRegExpLiteral(RegExpLiteral node, WalkContext arg) {
		CAstNode flagsNode = Ast.makeConstant(node.getFlags());
		CAstNode valNode = Ast.makeConstant(node.getValue());
		return handleNew(arg, "RegExp", new CAstNode[]{ flagsNode, valNode });
	}

	@Override
	public CAstNode visitReturnStatement(ReturnStatement node, WalkContext arg) {
		AstNode val = node.getReturnValue();
		if (val != null) {
			return Ast.makeNode(CAstNode.RETURN, visit(val, arg));
		} else {
			return Ast.makeNode(CAstNode.RETURN);
		}
	}

	@Override
	public CAstNode visitScope(Scope node, WalkContext arg) {
		List<CAstNode> nodes = new ArrayList<CAstNode>();
		for(Node child : node) {
			nodes.add(visit((AstNode)child, arg));
		}
		if (nodes.isEmpty()) {
			return Ast.makeNode(CAstNode.EMPTY);
		} else {
			return 
				Ast.makeNode(CAstNode.LOCAL_SCOPE,
					Ast.makeNode(CAstNode.BLOCK_STMT, nodes.toArray(new CAstNode[ nodes.size() ])));
		}
	}

	@Override
	public CAstNode visitScriptNode(ScriptNode node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitStringLiteral(StringLiteral node, WalkContext arg) {
		return Ast.makeConstant(node.getValue());
	}

	@Override
	public CAstNode visitSwitchCase(SwitchCase node, WalkContext arg) {
		if (node.getStatements() == null) {
			return Ast.makeNode(CAstNode.EMPTY);
		} else {
		CAstNode[] stmts = new CAstNode[ node.getStatements().size() ];
		for(int i = 0; i < stmts.length; i++) {
			stmts[i] = visit(node.getStatements().get(i), arg);
		}
		return Ast.makeNode(CAstNode.BLOCK_STMT, stmts);
		}
	}

	@Override
	public CAstNode visitSwitchStatement(SwitchStatement node, WalkContext context) {
    AstNode breakStmt = makeEmptyLabelStmt("breakLabel");
    CAstNode breakLabel = visit(breakStmt, context);

    WalkContext switchBodyContext = makeBreakContext(node, context, breakStmt);

    int i = 0;
		CAstNode[] children = new CAstNode[ node.getCases().size() * 2 ];
		for(SwitchCase sc : node.getCases()) {
			CAstNode label = 
				Ast.makeNode(CAstNode.LABEL_STMT, 
						Ast.makeConstant(String.valueOf(i/2)), 
						Ast.makeNode(CAstNode.EMPTY));
			context.cfg().map(label, label);
			children[i++] = label;
			
			if (sc.isDefault()) {
				context.cfg().add(node, label, CAstControlFlowMap.SWITCH_DEFAULT);
			} else {
				CAstNode labelCAst = visit(sc.getExpression(), context);
				context.cfg().add(node, label, labelCAst);
			}
			
			children[i++] = visit(sc, switchBodyContext);
		}
		
		CAstNode s = 
			Ast.makeNode(CAstNode.SWITCH, 
				visit(node.getExpression(), context), 
				Ast.makeNode(CAstNode.BLOCK_STMT, children));
		
		context.cfg().map(node, s);
		
		return Ast.makeNode(CAstNode.BLOCK_STMT, s, breakLabel);
	}

	@Override
	public CAstNode visitSymbol(Symbol node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitThrowStatement(ThrowStatement n, WalkContext context) {
	      CAstNode catchNode = context.getCatchTarget();
	      if (catchNode != null) {
	        context.cfg().add(n, context.getCatchTarget(), null);
	      } else {
	        context.cfg().add(n, CAstControlFlowMap.EXCEPTION_TO_EXIT, null);
	      }
	      
	      CAstNode throwAst = Ast.makeNode(CAstNode.THROW, visit(n.getExpression(), context));

	      context.cfg().map(n, throwAst);
	      return throwAst;
	}

	@Override
	public CAstNode visitTryStatement(TryStatement node, WalkContext arg) {
		List<CatchClause> catches = node.getCatchClauses();
		CAstNode tryCatch;
		
		if (catches != null && catches.size() > 0) {
			CAstNode var = Ast.makeConstant(catches.get(0).getVarName().getString());
			CAstNode code = Ast.makeNode(CAstNode.THROW, var);
			for(int i = catches.size()-1; i >= 0; i--) {
				CatchClause clause = catches.get(i);
				if (clause.getCatchCondition() != null) {
					code = Ast.makeNode(CAstNode.IF_STMT, visit(clause.getCatchCondition(), arg), visit(clause.getBody(), arg), code);
				} else {
					code = visit(clause, arg);
				}
			}
			CAstNode catchBlock = Ast.makeNode(CAstNode.CATCH, var, code);
			arg.cfg().map(catchBlock, catchBlock);
			
			TryCatchContext tryContext = new TryCatchContext(arg, catchBlock);
			tryCatch = Ast.makeNode(CAstNode.TRY, visit(node.getTryBlock(), tryContext), catchBlock);
		} else {
			tryCatch = visit(node.getTryBlock(), arg);
		}
		
		if (node.getFinallyBlock() != null) {
			return Ast.makeNode(CAstNode.UNWIND, tryCatch, visit(node.getFinallyBlock(), arg));
		} else {
			return tryCatch;
		}
	}

	@Override
	public CAstNode visitUnaryExpression(UnaryExpression node, WalkContext arg) {
		if (node.getType() == Token.INC || node.getType() == Token.DEC) {
			CAstNode op = (node.getType() == Token.DEC) ? CAstOperator.OP_SUB : CAstOperator.OP_ADD;

			AstNode l = node.getOperand();
			CAstNode last = visit(l, arg);

			return Ast.makeNode((node.isPostfix() ? CAstNode.ASSIGN_POST_OP : CAstNode.ASSIGN_PRE_OP), last,
					Ast.makeConstant(1), op);
			
		} else if (node.getType() == Token.TYPEOFNAME) {
	        return Ast.makeNode(CAstNode.TYPE_OF, Ast.makeNode(CAstNode.VAR, Ast.makeConstant(node.getString())));
		}	else if (node.getType() == Token.TYPEOF) {
	        return Ast.makeNode(CAstNode.TYPE_OF, visit(node.getOperand(), arg));
    } else if (node.getType() == Token.DELPROP) {
      AstNode expr = node.getOperand();
      if (expr instanceof FunctionCall) {
        expr = ((FunctionCall) expr).getTarget();
        assert expr instanceof PropertyGet;
      }
      
      return Ast.makeNode(CAstNode.ASSIGN, visit(expr, arg), Ast.makeConstant(null));
		} else if (node.getType() == Token.VOID) {
		  return Ast.makeConstant(null);
		} else {
			return Ast.makeNode(CAstNode.UNARY_EXPR, translateOpcode(node.getOperator()), visit(node.getOperand(), arg));
		}
	}

	@Override
	public CAstNode visitVariableDeclaration(VariableDeclaration node,
			WalkContext arg) {
		List<VariableInitializer> inits = node.getVariables();
		CAstNode[] children = new CAstNode[ inits.size() ];
		int i = 0;
		for(VariableInitializer init : inits) {
			arg.addNameDecl(
					noteSourcePosition(
							arg,
							Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl(init.getTarget().getString())),
									readName(arg, "$$undefined")),
							node));
				
			if (init.getInitializer() == null) {
			  children[i++] = Ast.makeNode(CAstNode.EMPTY);
			} else {
			  children[i++] = 
			    Ast.makeNode(CAstNode.ASSIGN, 
			      Ast.makeNode(CAstNode.VAR, Ast.makeConstant(init.getTarget().getString())),
			      visit(init, arg));
			}
		}
		
		return Ast.makeNode(CAstNode.BLOCK_STMT, children);
	}

	@Override
	public CAstNode visitVariableInitializer(VariableInitializer node, WalkContext context) {
		if (node.getInitializer() != null) {
			return visit(node.getInitializer(), context);	
		} else {
			return Ast.makeNode(CAstNode.EMPTY);
		}
	}

	@Override
	public CAstNode visitWhileLoop(WhileLoop node, WalkContext arg) {
		AstNode breakStmt = makeEmptyLabelStmt("breakLabel");
		CAstNode breakLabel = visit(breakStmt, arg);
		AstNode contStmt = makeEmptyLabelStmt("contLabel");
		CAstNode contLabel = visit(contStmt, arg);

		WalkContext loopContext = makeLoopContext(node, arg, breakStmt, contStmt);
	
		CAstNode loop = Ast.makeNode(CAstNode.BLOCK_STMT, 
		   Ast.makeNode(CAstNode.LOOP, 
				   visit(node.getCondition(), arg), 
				   Ast.makeNode(CAstNode.BLOCK_STMT,
				     visit(node.getBody(), loopContext),
				     contLabel)),
		   breakLabel);
		
		arg.cfg().map(node, loop);
		
		return loop;
	}

	@Override
	public CAstNode visitWithStatement(WithStatement node, WalkContext arg) {
		// TODO implement this somehow
		return Ast.makeNode(CAstNode.EMPTY);
	}

	@Override
	public CAstNode visitXmlDotQuery(XmlDotQuery node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitXmlElemRef(XmlElemRef node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitXmlExpression(XmlExpression node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitXmlFragment(XmlFragment node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitXmlLiteral(XmlLiteral node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitXmlMemberGet(XmlMemberGet node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitXmlPropRef(XmlPropRef node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitXmlRef(XmlRef node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitXmlString(XmlString node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CAstNode visitYield(Yield node, WalkContext arg) {
		// TODO Auto-generated method stub
		return null;
	}
	  
  }
  
  /*
  private CAstNode walkNodesInternal(final Node n, WalkContext context) {
    final int NT = n.getType();
    System.err.println(NT + " " + n.getClass());
    switch (NT) {

    case Token.FUNCTION: {
      //int fnIndex = n.getExistingIntProp(Node.FUNCTION_PROP);
      //FunctionNode fn = context.top().getFunctionNode(fnIndex);

      FunctionNode fn = (FunctionNode)n;
      
      CAstEntity fne = walkEntity(fn, context);

      System.err.println(fne.getName());
      
      if (fn.getFunctionType() == FunctionNode.FUNCTION_EXPRESSION) {
        CAstNode fun = Ast.makeNode(CAstNode.FUNCTION_EXPR, Ast.makeConstant(fne));

        context.addScopedEntity(fun, fne);

        return fun;

      } else {
        context.addNameDecl(Ast.makeNode(CAstNode.FUNCTION_STMT, Ast.makeConstant(fne)));

        context.addScopedEntity(null, fne);

        return Ast.makeNode(CAstNode.EMPTY);
      }
    }

    case Token.CATCH_SCOPE: {
      Node catchVarNode = n.getFirstChild();
      String catchVarName = catchVarNode.getString();
      assert catchVarName != null;
      context.setCatchVar(catchVarName);
      return Ast.makeNode(CAstNode.EMPTY);
    }

    case Token.LOCAL_BLOCK: {
      return Ast.makeNode(CAstNode.BLOCK_EXPR, walkChildren(n, context));
    }

    case Token.TRY: {
      Node catchNode = ((Jump) n).target;
      Node finallyNode = ((Jump) n).getFinally();

      ArrayList<Node> tryList = new ArrayList<Node>();
      ArrayList<Node> catchList = new ArrayList<Node>();
      ArrayList<Node> finallyList = new ArrayList<Node>();
      ArrayList<Node> current = tryList;
      Node c;
      for (c = n.getFirstChild(); c.getNext() != null; c = c.getNext()) {
        if (c == catchNode) {
          current = catchList;
        } else if (c == finallyNode) {
          current = finallyList;
        }

        if (c.getType() == Token.GOTO &&
        // ((Node.Jump)c).target == lastChildNode &&
            (c.getNext() == catchNode || c.getNext() == finallyNode)) {
          continue;
        }

        current.add(c);
      }

      CAstNode finallyBlock = null;
      if (finallyNode != null) {
        int i = 0;
        CAstNode[] finallyAsts = new CAstNode[finallyList.size()];
        for (Iterator<Node> fns = finallyList.iterator(); fns.hasNext();) {
          finallyAsts[i++] = walkNodes(fns.next(), context);
        }
        finallyBlock = Ast.makeNode(CAstNode.BLOCK_STMT, finallyAsts);
      }

      if (catchNode != null) {

        int i = 0;
        WalkContext catchChild = new CatchBlockContext(context);
        CAstNode[] catchAsts = new CAstNode[catchList.size()];
        for (Iterator<Node> cns = catchList.iterator(); cns.hasNext();) {
          catchAsts[i++] = walkNodes(cns.next(), catchChild);
        }
        CAstNode catchBlock = Ast.makeNode(CAstNode.CATCH, Ast.makeConstant(catchChild.getCatchVar()),
            Ast.makeNode(CAstNode.BLOCK_STMT, catchAsts));
        context.cfg().map(catchBlock, catchBlock);

        i = 0;
        WalkContext tryChild = new TryBlockContext(context, catchBlock);
        CAstNode[] tryAsts = new CAstNode[tryList.size()];
        for (Iterator<Node> tns = tryList.iterator(); tns.hasNext();) {
          tryAsts[i++] = walkNodes(tns.next(), tryChild);
        }
        CAstNode tryBlock = Ast.makeNode(CAstNode.BLOCK_STMT, tryAsts);

        if (finallyBlock != null) {
          return Ast.makeNode(CAstNode.BLOCK_STMT,
              Ast.makeNode(CAstNode.UNWIND, Ast.makeNode(CAstNode.TRY, tryBlock, catchBlock), finallyBlock), walkNodes(c, context));
        } else {
          return Ast.makeNode(CAstNode.BLOCK_STMT, Ast.makeNode(CAstNode.TRY, tryBlock, catchBlock), walkNodes(c, context));
        }

      } else {
        int i = 0;
        CAstNode[] tryAsts = new CAstNode[tryList.size()];
        for (Iterator<Node> tns = tryList.iterator(); tns.hasNext();) {
          tryAsts[i++] = walkNodes(tns.next(), context);
        }
        CAstNode tryBlock = Ast.makeNode(CAstNode.BLOCK_STMT, tryAsts);

        return Ast.makeNode(
            CAstNode.BLOCK_STMT,
            Ast.makeNode(CAstNode.UNWIND, Ast.makeNode(CAstNode.BLOCK_STMT, tryBlock),
                Ast.makeNode(CAstNode.BLOCK_STMT, finallyBlock)), walkNodes(c, context));
      }
    }

    case Token.JSR: {
      return Ast.makeNode(CAstNode.EMPTY);
      /*
       * Node jsrTarget = ((Node.Jump)n).target; Node finallyNode =
       * jsrTarget.getNext(); return walkNodes(finallyNode, context);
       *
    }


      /*
       * case Token.ENTERWITH: { return
       * Ast.makeNode(JavaScriptCAstNode.ENTER_WITH,
       * walkNodes(n.getFirstChild(), context)); }
       * 
       * case Token.LEAVEWITH: { return
       * Ast.makeNode(JavaScriptCAstNode.EXIT_WITH, Ast.makeConstant(null)); }
       *
    case Token.ENTERWITH:
    case Token.LEAVEWITH: {
      return Ast.makeNode(CAstNode.EMPTY);
    }

    case Token.LOOP: {
      LoopContext child = new LoopContext(context);
      CAstNode[] nodes = walkChildren(n, child);

      if (child.forInInitExpr != null) {
        String nm = child.forInVar;
        return Ast.makeNode(CAstNode.BLOCK_STMT, Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl(nm, true)),
            walkNodes(child.forInInitExpr, context)), nodes);
      } else {
        return Ast.makeNode(CAstNode.BLOCK_STMT, nodes);
      }
    }

    case Token.SWITCH: {
      SwitchStatement s = (SwitchStatement) n;
      Node switchValue = s.getExpression();

      CAstNode defaultLabel = Ast.makeNode(CAstNode.LABEL_STMT, Ast.makeNode(CAstNode.EMPTY));
      context.cfg().map(defaultLabel, defaultLabel);

      List<CAstNode> labelCasts = new ArrayList<CAstNode>();
      List<CAstNode> codeCasts = new ArrayList<CAstNode>();
      for (SwitchCase kase : s.getCases()) {
    	  assert kase.getType() == Token.CASE;
    	  Node caseLbl = kase.getExpression();
    	  CAstNode labelCast = walkNodes(caseLbl, context);
    	  labelCasts.add(labelCast);

    	  CAstNode targetCast = Ast.makeNode(CAstNode.EMPTY);
    	  context.cfg().map(targetCast, labelCast);
    	  codeCasts.add(targetCast);
    	  context.cfg().add(s, targetCast, labelCast);
            
    	  for(Node target : kase.getStatements()) {
    		  codeCasts.add(walkNodes(target, context));
    	  } 
      }
      
      CAstNode[] children = codeCasts.toArray(new CAstNode[codeCasts.size()]);

      context.cfg().add(s, defaultLabel, CAstControlFlowMap.SWITCH_DEFAULT);
      CAstNode switchAst = Ast.makeNode(CAstNode.SWITCH, walkNodes(switchValue, context), children);
      noteSourcePosition(context, switchAst, s);
      context.cfg().map(s, switchAst);
      return switchAst;
    }

    case Token.WITH:
    case Token.FINALLY:
    case Token.BLOCK:
    case Token.LABEL: {
      return Ast.makeNode(CAstNode.BLOCK_STMT, walkChildren(n, context));
    }

    case Token.EXPR_VOID:
    case Token.EXPR_RESULT: {
      WalkContext child = new ExpressionContext(context);
      Node expr = ((ExpressionStatement) n).getExpression();

      if (NT == Token.EXPR_RESULT) {
        // EXPR_RESULT node is just a wrapper, so if we care about base pointer
        // of n, we
        // care about child of n
        child.updateBase(n, expr);
      }

      return walkNodes(expr, child);
    }


    case Token.CALL: {
      if (!isPrimitiveCall(context, n)) {
        CAstNode base = makeVarRef("$$ base");
        Node callee = (n instanceof FunctionCall) ? ((FunctionCall)n).getTarget() : n.getFirstChild();
        WalkContext child = new BaseCollectingContext(context, callee, "$$ base");
        CAstNode fun = walkNodes(callee, child);

        // the first actual parameter appearing within the parentheses of the
        // call (i.e., possibly excluding the 'this' parameter)
        CAstNode[] args = gatherCallArguments(n, context);
        if (child.foundBase(callee))
          return Ast.makeNode(
              CAstNode.LOCAL_SCOPE,
              Ast.makeNode(CAstNode.BLOCK_EXPR,
                  Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl("$$ base")), Ast.makeConstant(null)),
                  makeCall(fun, base, args, context)));
<<<<<<< .mine
        else
          return makeCall(fun, Ast.makeConstant(null), args, context);
=======
        else {
          // pass the global object as the receiver argument
          return makeCall(fun, makeVarRef(JSSSAPropagationCallGraphBuilder.GLOBAL_OBJ_VAR_NAME), firstParamInParens, context);
        }
>>>>>>> .r4418
      } else {
        return Ast.makeNode(CAstNode.PRIMITIVE, gatherCallArguments(n, context));
      }
    }

    case Token.BINDNAME:
    case Token.NAME: {
      return readName(context, n.getString());
    }

    case Token.THIS: {
      return makeVarRef("this");
    }

    case Token.THISFN: {
      return makeVarRef(((FunctionNode) context.top()).getFunctionName());
    }

    case Token.STRING: {
    	if (n instanceof StringLiteral) {
    		return Ast.makeConstant(((StringLiteral)n).getValue());
    	} else {
    		return Ast.makeConstant(n.getString());
    	}
    }

    case Token.NUMBER: {
      return Ast.makeConstant(n.getDouble());
    }

    case Token.FALSE: {
      return Ast.makeConstant(false);
    }

    case Token.TRUE: {
      return Ast.makeConstant(true);
    }

    case Token.NULL:
    case Token.VOID: {
      return Ast.makeConstant(null);
    }

    case Token.ADD:
    case Token.DIV:
    case Token.LSH:
    case Token.MOD:
    case Token.MUL:
    case Token.RSH:
    case Token.SUB:
    case Token.URSH:
    case Token.BITAND:
    case Token.BITOR:
    case Token.BITXOR:
    case Token.EQ:
    case Token.SHEQ:
    case Token.GE:
    case Token.GT:
    case Token.LE:
    case Token.LT:
    case Token.SHNE:
    case Token.NE: {
      Node l; 
      Node r; 
      if (n instanceof InfixExpression) {
    	  l = ((InfixExpression)n).getLeft();
    	  r = ((InfixExpression)n).getRight();
      } else {
    	  l = n.getFirstChild();
    	  r = l.getNext();
      }
      return Ast.makeNode(CAstNode.BINARY_EXPR, translateOpcode(NT), walkNodes(l, context), walkNodes(r, context));
    }

    case Token.BITNOT:
    case Token.NOT: {
      return Ast.makeNode(CAstNode.UNARY_EXPR, translateOpcode(NT), walkNodes(((UnaryExpression)n).getOperand(), context));
    }

    case Token.VAR:
    case Token.CONST: {
      List<CAstNode> result = new ArrayList<CAstNode>();
      if (n instanceof VariableDeclaration) {
    	  for(VariableInitializer var : ((VariableDeclaration)n).getVariables()) {
       		  context.addNameDecl(Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl(var.getTarget().getString())),
    				  readName(context, "$$undefined")));
    		 if (var.getInitializer() != null) {
      			  WalkContext child = new ExpressionContext(context);

    			  result.add(Ast.makeNode(CAstNode.ASSIGN, Ast.makeNode(CAstNode.VAR, Ast.makeConstant(var.getTarget().getString())),
    			    walkNodes(var.getInitializer(), child)));	 
    		 }
    	  }
      } else {
    	  Node nm = n.getFirstChild();
    	  while (nm != null) {
    		  context.addNameDecl(Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl(nm.getString())),
    				  readName(context, "$$undefined")));

<<<<<<< .mine
    		  if (nm.getFirstChild() != null) {
    			  WalkContext child = new ExpressionContext(context);
=======
          result.add(Ast.makeNode(CAstNode.ASSIGN, makeVarRef(nm.getString()),
              walkNodes(nm.getFirstChild(), child)));
>>>>>>> .r4418

    			  result.add(Ast.makeNode(CAstNode.ASSIGN, Ast.makeNode(CAstNode.VAR, Ast.makeConstant(nm.getString())),
    			    walkNodes(nm.getFirstChild(), child)));

    		  }

    		  nm = nm.getNext();
    	  }
      }
      
      if (result.size() > 0) {
        return Ast.makeNode(CAstNode.BLOCK_EXPR, result.toArray(new CAstNode[result.size()]));
      } else {
        return Ast.makeNode(CAstNode.EMPTY);
      }
    }

    case Token.REGEXP: {
      int regexIdx = n.getIntProp(Node.REGEXP_PROP, -1);

      assert regexIdx != -1 : "while converting: " + context.top().toStringTree(context.top()) + "\nlooking at bad regex:\n "
          + n.toStringTree(context.top());

      String flags = context.top().getRegexpFlags(regexIdx);
      CAstNode flagsNode = Ast.makeConstant(flags);

      String str = context.top().getRegexpString(regexIdx);
      Node strNode = Node.newString(str);

      return handleNew(context, "RegExp", new CAstNode[]{ flagsNode });
    }

    case Token.ENUM_INIT_KEYS: {
      context.createForInVar(n.getFirstChild());
      return Ast.makeNode(CAstNode.EMPTY);
    }

    case Token.ENUM_ID: {
      return Ast.makeNode(CAstNode.EACH_ELEMENT_GET, makeVarRef(context.getForInInitVar()));
    }

    case Token.ENUM_NEXT: {
      return Ast.makeNode(CAstNode.EACH_ELEMENT_HAS_NEXT, makeVarRef(context.getForInInitVar()));
    }

    case Token.RETURN: {
      Node val = n.getFirstChild();
      if (val != null) {
        WalkContext child = new ExpressionContext(context);
        return Ast.makeNode(CAstNode.RETURN, walkNodes(val, child));
      } else {
        return Ast.makeNode(CAstNode.RETURN);
      }
    }

    case Token.ASSIGN: 
    {
    	Assignment a = (Assignment) n;
    	return Ast.makeNode(CAstNode.ASSIGN, walkNodes(a.getLeft(), context), walkNodes(a.getRight(), context));
    }

    case Token.ASSIGN_ADD:
    case Token.ASSIGN_BITAND:
    case Token.ASSIGN_BITOR:
    case Token.ASSIGN_BITXOR:
    case Token.ASSIGN_DIV:
    case Token.ASSIGN_LSH:
    case Token.ASSIGN_MOD:
    case Token.ASSIGN_MUL:
    case Token.ASSIGN_RSH:
    case Token.ASSIGN_SUB:
    case Token.ASSIGN_URSH: {
    	Assignment a = (Assignment) n;
    	return Ast.makeNode(CAstNode.ASSIGN_POST_OP, 
    			walkNodes(a.getLeft(), context), 
    			walkNodes(a.getRight(), context),
       			translateOpcode(a.getOperator()));
    }

    case Token.SETNAME: {
      Node nm = n.getFirstChild();
      return Ast.makeNode(CAstNode.ASSIGN, walkNodes(nm, context), walkNodes(nm.getNext(), context));
    }

    case Token.IFNE:
    case Token.IFEQ: {
      context.cfg().add(n, ((Jump) n).target, Boolean.TRUE);
      WalkContext child = new ExpressionContext(context);
      CAstNode gotoAst = Ast.makeNode(CAstNode.IFGOTO, translateOpcode(NT), walkNodes(n.getFirstChild(), child),
          Ast.makeConstant(1));

      context.cfg().map(n, gotoAst);
      return gotoAst;
    }

    case Token.GOTO: {
      context.cfg().add(n, ((Jump) n).target, null);
      CAstNode gotoAst = Ast.makeNode(CAstNode.GOTO, Ast.makeConstant(((Jump) n).target.labelId()));

      context.cfg().map(n, gotoAst);
      return gotoAst;
    }

    case Token.BREAK: {
      context.cfg().add(n, ((Jump) n).getJumpStatement().target, null);
      CAstNode gotoAst = Ast.makeNode(CAstNode.GOTO, Ast.makeConstant(((Jump) n).getJumpStatement().target.labelId()));

      context.cfg().map(n, gotoAst);
      return gotoAst;
    }

    case Token.CONTINUE: {
      context.cfg().add(n, ((Jump) n).getJumpStatement().getContinue(), null);
      CAstNode gotoAst = Ast.makeNode(CAstNode.GOTO, Ast.makeConstant(((Jump) n).getJumpStatement().getContinue().labelId()));

      context.cfg().map(n, gotoAst);
      return gotoAst;
    }

    case Token.TARGET: {
      CAstNode result = Ast.makeNode(CAstNode.LABEL_STMT, Ast.makeConstant(n.labelId()), Ast.makeNode(CAstNode.EMPTY));

      context.cfg().map(n, result);
      return result;
    }

<<<<<<< .mine
 
=======
    case Token.OR: {
    	Node l = n.getFirstChild();
    	Node r = l.getNext();
    	CAstNode lhs = walkNodes(l, context);
    	String lhsTempName = "or___lhs";
    	// { lhsTemp := <lhs>; if(lhsTemp) { lhsTemp } else { <rhs> }
    	return Ast.makeNode(
    			CAstNode.LOCAL_SCOPE,
    			Ast.makeNode(CAstNode.BLOCK_EXPR,
    					Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl(lhsTempName)), lhs),
    					Ast.makeNode(CAstNode.IF_EXPR, makeVarRef(lhsTempName), makeVarRef(lhsTempName), walkNodes(r, context))));
    }

    case Token.AND: {
      Node l = n.getFirstChild();
      Node r = l.getNext();
      CAstNode lhs = walkNodes(l, context);
      String lhsTempName = "and___lhs";
      // { lhsTemp := <lhs>; if(lhsTemp) { <rhs> } else { lhsTemp }
      return Ast.makeNode(
    		  CAstNode.LOCAL_SCOPE,
    		  Ast.makeNode(CAstNode.BLOCK_EXPR,
    				  Ast.makeNode(CAstNode.DECL_STMT, Ast.makeConstant(new CAstSymbolImpl(lhsTempName)), lhs),
    				  Ast.makeNode(CAstNode.IF_EXPR, makeVarRef(lhsTempName), walkNodes(r, context), makeVarRef(lhsTempName))));
    }

>>>>>>> .r4418
    case Token.HOOK: {
      Node cond = n.getFirstChild();
      Node thenBranch = cond.getNext();
      Node elseBranch = thenBranch.getNext();
      return Ast.makeNode(CAstNode.IF_EXPR, walkNodes(cond, context), walkNodes(thenBranch, context),
          walkNodes(elseBranch, context));

    }


    case Token.NEW: {
      if (isPrimitiveCreation(context, n)) {
        return makeBuiltinNew(getNewTarget(n).getString());
      } else {
        Node receiver = ((NewExpression)n).getTarget();
        return handleNew(context, walkNodes(receiver, context), gatherCallArguments(n, context));
      }
    }

    case Token.ARRAYLIT: {
      int count = 0;
      for (Node x = n.getFirstChild(); x != null; count++, x = x.getNext())
        ;

      int i = 0;
      CAstNode[] args = new CAstNode[2 * count + 1];
      args[i++] = (isPrologueScript(context)) ? makeBuiltinNew("Array") : handleNew(context, "Array", null);

      int[] skips = (int[]) n.getProp(Node.SKIP_INDEXES_PROP);
      int skip = 0;
      int idx = 0;
      Node elt = n.getFirstChild();
      while (elt != null) {
        if (skips != null && skip < skips.length && skips[skip] == idx) {
          skip++;
          idx++;
          continue;
        }

        args[i++] = Ast.makeConstant(idx++);
        args[i++] = walkNodes(elt, context);

        elt = elt.getNext();
      }

      return Ast.makeNode(CAstNode.OBJECT_LITERAL, args);
    }

    case Token.OBJECTLIT: {
    	CAstNode[] args;
    	if (n instanceof ObjectLiteral) {
    		List<ObjectProperty> props = ((ObjectLiteral)n).getElements();
    		args = new CAstNode[props.size() * 2 + 1];
    		int i = 0;
    		args[i++] = ((isPrologueScript(context)) ? makeBuiltinNew("Object") : handleNew(context, "Object", null));
    		for(ObjectProperty prop : props) {
    			args[i++] = walkNodes(prop.getLeft(), context);
    			args[i++] = walkNodes(prop.getRight(), context);
    		}
    	} else {
    		Object[] propertyList = (Object[]) n.getProp(Node.OBJECT_IDS_PROP);
    		args = new CAstNode[propertyList.length * 2 + 1];
    		int i = 0;
    		args[i++] = ((isPrologueScript(context)) ? makeBuiltinNew("Object") : handleNew(context, "Object", null));

    		Node val = n.getFirstChild();
    		int nameIdx = 0;
    		for (; nameIdx < propertyList.length; nameIdx++, val = val.getNext()) {
    			args[i++] = Ast.makeConstant(propertyList[nameIdx]);
    			args[i++] = walkNodes(val, context);
    		}
    	}

      return Ast.makeNode(CAstNode.OBJECT_LITERAL, args);
    }

    case Token.GETPROP:
    case Token.GETELEM: {
<<<<<<< .mine
      Node receiver; 
      Node element; 
      if (n instanceof PropertyGet) {
    	  receiver = ((PropertyGet)n).getLeft();
    	  element = ((PropertyGet)n).getRight();
=======
      Node receiver = n.getFirstChild();
      Node element = receiver.getNext();

      CAstNode rcvr = walkNodes(receiver, context);
      String baseVarName = context.getBaseVarNameIfRelevant(n);

      CAstNode elt = walkNodes(element, context);

      CAstNode get, result;
      if (baseVarName != null) {
        result = Ast.makeNode(CAstNode.BLOCK_EXPR, Ast.makeNode(CAstNode.ASSIGN, makeVarRef(baseVarName), rcvr),
            get = Ast.makeNode(CAstNode.OBJECT_REF, makeVarRef(baseVarName), elt));
>>>>>>> .r4418
      } else {
    	  receiver = ((ElementGet)n).getTarget();
    	  element = ((ElementGet)n).getElement();
      }
    
      return handleFieldGet(n, context, receiver, element);
    }
    
    case Token.GET_REF: {
      // read of __proto__
      // first and only child c1 is of type Token.REF_SPECIAL whose NAME_PROP property should be "__proto__".  
      // c1 has a single child, the base pointer for the reference
      Node child1 = n.getFirstChild();
      assert child1.getType() == Token.REF_SPECIAL;
      assert child1.getProp(Node.NAME_PROP).equals("__proto__");
      Node receiver = child1.getFirstChild();     
      assert child1.getNext() == null;
      CAstNode rcvr = walkNodes(receiver, context);
      final CAstNode result = Ast.makeNode(CAstNode.OBJECT_REF, rcvr, Ast.makeConstant("__proto__"));
      
      if (context.getCatchTarget() != null) {
        context.cfg().map(result, result);
        context.cfg().add(result, context.getCatchTarget(), JavaScriptTypes.TypeError);
      }
      
      return result;
    }

    case Token.SETPROP:
    case Token.SETELEM: {
      Node receiver = n.getFirstChild();
      Node elt = receiver.getNext();
      Node val = elt.getNext();

      CAstNode rcvr = walkNodes(receiver, context);

      return Ast.makeNode(CAstNode.ASSIGN, Ast.makeNode(CAstNode.OBJECT_REF, rcvr, walkNodes(elt, context)),
          walkNodes(val, context));
    }
    
    case Token.SET_REF: {
      // first child c1 is of type Token.REF_SPECIAL whose NAME_PROP property should be "__proto__".  
      // c1 has a single child, the base pointer for the reference
      // second child c2 is RHS of assignment
      Node child1 = n.getFirstChild();
      assert child1.getType() == Token.REF_SPECIAL;
      assert child1.getProp(Node.NAME_PROP).equals("__proto__");
      Node receiver = child1.getFirstChild();
      Node val = child1.getNext();

      CAstNode rcvr = walkNodes(receiver, context);

      return Ast.makeNode(CAstNode.ASSIGN, Ast.makeNode(CAstNode.OBJECT_REF, rcvr, Ast.makeConstant("__proto__")),
          walkNodes(val, context));
    }
      

    case Token.DELPROP: {
      Node receiver = n.getFirstChild();
      Node element = receiver.getNext();

      CAstNode rcvr = walkNodes(receiver, context);
      String baseVarName = context.getBaseVarNameIfRelevant(n);

      CAstNode elt = walkNodes(element, context);

      if (baseVarName != null) {
        return Ast.makeNode(CAstNode.BLOCK_EXPR, Ast.makeNode(CAstNode.ASSIGN, makeVarRef(baseVarName), rcvr),
            Ast.makeNode(CAstNode.ASSIGN, Ast.makeNode(CAstNode.OBJECT_REF, makeVarRef(baseVarName), elt), Ast.makeConstant(null)));
      } else {
        return Ast.makeNode(CAstNode.ASSIGN, Ast.makeNode(CAstNode.OBJECT_REF, rcvr, elt), Ast.makeConstant(null));
      }
    }

<<<<<<< .mine
=======
    case Token.TYPEOFNAME: {
      return Ast.makeNode(CAstNode.TYPE_OF, makeVarRef(n.getString()));
    }
>>>>>>> .r4418

    case Token.SETPROP_OP:
    case Token.SETELEM_OP: {
      Node receiver = n.getFirstChild();
      Node elt = receiver.getNext();
      Node op = elt.getNext();

      CAstNode rcvr = walkNodes(receiver, context);

      return Ast.makeNode(CAstNode.ASSIGN_POST_OP, Ast.makeNode(CAstNode.OBJECT_REF, rcvr, walkNodes(elt, context)),
          walkNodes(op.getFirstChild().getNext(), context), translateOpcode(op.getType()));
    }

    case Token.THROW: {
      CAstNode catchNode = context.getCatchTarget();
      if (catchNode != null)
        context.cfg().add(n, context.getCatchTarget(), null);
      else
        context.cfg().add(n, CAstControlFlowMap.EXCEPTION_TO_EXIT, null);

      CAstNode throwAst = Ast.makeNode(CAstNode.THROW, walkNodes(n.getFirstChild(), context));

      context.cfg().map(n, throwAst);
      return throwAst;
    }

    case Token.EMPTY: {
      return Ast.makeConstant(null);
    }


    case Token.IN: {
      Node value = n.getFirstChild();
      Node property = value.getNext();
      return Ast.makeNode(CAstNode.IS_DEFINED_EXPR, walkNodes(property, context), walkNodes(value, context));
    }

    case Token.IF: {
    	IfStatement stmt = (IfStatement)n;
    	if (stmt.getElsePart() != null) {
    		return Ast.makeNode(CAstNode.IF_STMT,
    			walkNodes(stmt.getCondition(), context),
    			walkNodes(stmt.getThenPart(), context),
    			walkNodes(stmt.getElsePart(), context));
    	} else {
    		return Ast.makeNode(CAstNode.IF_STMT,
        			walkNodes(stmt.getCondition(), context),
        			walkNodes(stmt.getThenPart(), context));
    	}
    }
    
    case Token.FOR: {
    	ForLoop f = (ForLoop) n;
    	return Ast.makeNode(CAstNode.BLOCK_STMT,
    			walkNodes(f.getInitializer(), context),
    			Ast.makeNode(CAstNode.LOOP,
    					walkNodes(f.getCondition(), context),
    					walkNodes(f.getBody(), context),
    					walkNodes(f.getIncrement(), context)));
    }
    
    case Token.LP: {
    	return walkNodes(((ParenthesizedExpression)n).getExpression(), context);
    }
    
    default: {
      System.err.println("while converting: " + context.top().toStringTree(context.top()) + "\nlooking at unhandled:\n "
          + n.toStringTree(context.top()) + "\n(of type " + NT + ") (of class " + n.getClass() + ")" + " at " + n.getLineno());

      Assertions.UNREACHABLE();
      return null;
    }
    }
  }

<<<<<<< .mine
private CAstNode handleFieldGet(final Node n, WalkContext context,
		Node receiver, Node element) {
	CAstNode rcvr = walkNodes(receiver, context);
      CAstNode baseVar = context.getBaseVarIfRelevant(n);

      CAstNode elt = walkNodes(element, context);

      CAstNode get, result;
      if (baseVar != null) {
        result = Ast.makeNode(CAstNode.BLOCK_EXPR, Ast.makeNode(CAstNode.ASSIGN, baseVar, rcvr),
            get = Ast.makeNode(CAstNode.OBJECT_REF, baseVar, elt));
      } else {
        result = get = Ast.makeNode(CAstNode.OBJECT_REF, rcvr, elt);
      }

      if (context.getCatchTarget() != null) {
        context.cfg().map(get, get);
        context.cfg().add(get, context.getCatchTarget(), JavaScriptTypes.TypeError);
      }

      return result;
}

private CAstNode[] walkChildren(final Node n, WalkContext context) {
	List<CAstNode> children = new ArrayList<CAstNode>();
      Iterator<Node> nodes = n.iterator();
      while (nodes.hasNext()) {
    	  children.add(walkNodes(nodes.next(), context));
      }
	return children.toArray(new CAstNode[ children.size() ]);
}

 /**
   * count the number of successor siblings of n, including n
   *
  private int countSiblingsStartingFrom(Node n) {
    int siblings = 0;
    for (Node c = n; c != null; c = c.getNext(), siblings++)
      ;
    return siblings;
  }

  private Iterator<? extends Node> varDeclGetVars(Node n) {
	  if (n instanceof VariableDeclaration) {
		  return ((VariableDeclaration)n).getVariables().iterator();
	  } else {
		  return new SiblingIterator(n.getFirstChild());
	  }
  }
  
*/
  
  private CAstNode makeVarRef(String varName) {
	  return Ast.makeNode(CAstNode.VAR, Ast.makeConstant(varName));
  }

  /**
   * parse the JavaScript code using Rhino, and then translate the resulting AST
   * to CAst
   */
  public CAstEntity translateToCAst() throws java.io.IOException {
    ToolErrorReporter reporter = new ToolErrorReporter(true);
    CompilerEnvirons compilerEnv = new CompilerEnvirons();
    compilerEnv.setErrorReporter(reporter);
    compilerEnv.setReservedKeywordAsIdentifier(true);
    compilerEnv.setIdeMode(true);
    
    if (DEBUG) {
      System.err.println(("translating " + scriptName + " with Rhino"));
    }
    
    Parser P = new Parser(compilerEnv, compilerEnv.getErrorReporter());

    AstRoot top = P.parse(sourceReader, scriptName, 1);

    final FunctionContext child = new ScriptContext(new RootContext(), top, top.getSourceName());
    TranslatingVisitor tv = new TranslatingVisitor();
    List<CAstNode> body = new ArrayList<CAstNode>();
    for(Node bn : top) {
    	body.add(tv.visit((AstNode)bn, child));
    }

    return walkEntity(top, body, top.getSourceName(), child);
  }

  private final CAst Ast;

  private final String scriptName;

  private final SourceModule sourceModule;

  final private Reader sourceReader;

  private int anonymousCounter = 0;

  private final DoLoopTranslator doLoopTranslator;
  
  public RhinoToAstTranslator(CAst Ast, SourceModule M, String scriptName, boolean replicateForDoLoops) {
    this.Ast = Ast;
    this.scriptName = scriptName;
    this.sourceModule = M;
    this.sourceReader = sourceModule.getInputReader();
    this.doLoopTranslator = new DoLoopTranslator(replicateForDoLoops, Ast);
  }

}