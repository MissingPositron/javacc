// Copyright 2018 Microsoft Inc. All Rights Reserved.
// Author: support@javacc.org (Sreeni Viswanadha)

package org.javacc.cpp;

import org.javacc.Version;
import org.javacc.jjtree.ASTBNFAction;
import org.javacc.jjtree.ASTBNFDeclaration;
import org.javacc.jjtree.ASTBNFNodeScope;
import org.javacc.jjtree.ASTBNFOneOrMore;
import org.javacc.jjtree.ASTBNFSequence;
import org.javacc.jjtree.ASTBNFTryBlock;
import org.javacc.jjtree.ASTBNFZeroOrMore;
import org.javacc.jjtree.ASTBNFZeroOrOne;
import org.javacc.jjtree.ASTCompilationUnit;
import org.javacc.jjtree.ASTExpansionNodeScope;
import org.javacc.jjtree.ASTGrammar;
import org.javacc.jjtree.ASTJavacodeBody;
import org.javacc.jjtree.ASTLHS;
import org.javacc.jjtree.ASTNodeDescriptor;
import org.javacc.jjtree.DefaultJJTreeVisitor;
import org.javacc.jjtree.IO;
import org.javacc.jjtree.JJTreeGlobals;
import org.javacc.jjtree.JJTreeNode;
import org.javacc.jjtree.JJTreeOptions;
import org.javacc.jjtree.JJTreeParserConstants;
import org.javacc.jjtree.Node;
import org.javacc.jjtree.NodeScope;
import org.javacc.jjtree.SimpleNode;
import org.javacc.jjtree.Token;
import org.javacc.jjtree.TokenUtils;
import org.javacc.parser.JavaCCGlobals;
import org.javacc.parser.Options;
import org.javacc.parser.OutputFile;
import org.javacc.utils.OutputFileGenerator;

import java.io.File;
import java.io.PrintWriter;
import java.util.Map;

public class JJTreeCodeGenerator extends DefaultJJTreeVisitor {

  static final String JJTStateVersion = Version.majorDotMinor;

  @Override
  public Object defaultVisit(SimpleNode node, Object data) {
    visit((JJTreeNode) node, data);
    return null;
  }

  @Override
  public Object visit(ASTGrammar node, Object data) {
    IO io = (IO) data;
    if(CodeGenerator.IS_DEBUG) 
    {
      io.println("/*@bgen(jjtree) " +
          JavaCCGlobals.getIdString("JJTree",
          new File(io.getOutputFileName()).getName()) +
           (Options.booleanValue("IGNORE_ACTIONS")  ? "" : " */"));
      io.print((Options.booleanValue("IGNORE_ACTIONS")  ? "" :"/*") + "@egen*/");
    }

    return node.childrenAccept(this, io);
  }

  @Override
  public Object visit(ASTBNFAction node, Object data) {
    IO io = (IO) data;
    /*
     * Assume that this action requires an early node close, and then try to decide whether this
     * assumption is false. Do this by looking outwards through the enclosing expansion units. If we
     * ever find that we are enclosed in a unit which is not the final unit in a sequence we know
     * that an early close is not required.
     */

    NodeScope ns = NodeScope.getEnclosingNodeScope(node);
    if (ns != null && !ns.isVoid()) {
      boolean needClose = true;
      Node sp = node.getScopingParent(ns);

      JJTreeNode n = node;
      while (true) {
        Node p = n.jjtGetParent();
        if (p instanceof ASTBNFSequence || p instanceof ASTBNFTryBlock) {
          if (n.getOrdinal() != p.jjtGetNumChildren() - 1) {
            /* We're not the final unit in the sequence. */
            needClose = false;
            break;
          }
        } else if (p instanceof ASTBNFZeroOrOne || p instanceof ASTBNFZeroOrMore || p instanceof ASTBNFOneOrMore) {
          needClose = false;
          break;
        }
        if (p == sp) {
          /* No more parents to look at. */
          break;
        }
        n = (JJTreeNode) p;
      }
      if (needClose) {
        openJJTreeComment(io, null);
        io.println();
        insertCloseNodeAction(ns, io, getIndentation(node));
        closeJJTreeComment(io);
      }
    }

    return visit((JJTreeNode) node, io);
  }

  @Override
  public Object visit(ASTBNFDeclaration node, Object data) {
    IO io = (IO) data;
    if (!node.node_scope.isVoid()) {
      String indent = "";
      if (TokenUtils.hasTokens(node)) {
        for (int i = 1; i < node.getFirstToken().beginColumn; ++i) {
          indent += " ";
        }
      } else {
        indent = "  ";
      }

      openJJTreeComment(io, node.node_scope.getNodeDescriptorText());
      io.println();
      insertOpenNodeCode(node.node_scope, io, indent);
      closeJJTreeComment(io);
    }

    return visit((JJTreeNode) node, io);
  }

  @Override
  public Object visit(ASTBNFNodeScope node, Object data) {
    IO io = (IO) data;
    if (node.node_scope.isVoid()) {
      return visit((JJTreeNode) node, io);
    }

    String indent = getIndentation(node.expansion_unit);

    openJJTreeComment(io, node.node_scope.getNodeDescriptor().getDescriptor());
    io.println();
    tryExpansionUnit(node.node_scope, io, indent, node.expansion_unit);
    return null;
  }

  @Override
  public Object visit(ASTCompilationUnit node, Object data) {
    IO io = (IO) data;
    Token t = node.getFirstToken();
    while (true) {
      node.print(t, io);
      if (t == node.getLastToken())
        break;
      if (t.kind == JJTreeParserConstants._PARSER_BEGIN) {
        // eat PARSER_BEGIN "(" <ID> ")"
        node.print(t.next, io);
        node.print(t.next.next, io);
        node.print(t = t.next.next.next, io);
      }

      t = t.next;
    }
    return null;
  }

  @Override
  public Object visit(ASTExpansionNodeScope node, Object data) {
    IO io = (IO) data;
    String indent = getIndentation(node.expansion_unit);
    openJJTreeComment(io, node.node_scope.getNodeDescriptor().getDescriptor());
    io.println();
    insertOpenNodeAction(node.node_scope, io, indent);
    tryExpansionUnit(node.node_scope, io, indent, node.expansion_unit);

    // Print the "whiteOut" equivalent of the Node descriptor to preserve
    // line numbers in the generated file.
    ((ASTNodeDescriptor) node.jjtGetChild(1)).jjtAccept(this, io);
    return null;
  }

  @Override
  public Object visit(ASTJavacodeBody node, Object data) {
    IO io = (IO) data;
    if (node.node_scope.isVoid()) {
      return visit((JJTreeNode) node, io);
    }

    Token first = node.getFirstToken();

    String indent = "";
    for (int i = 4; i < first.beginColumn; ++i) {
      indent += " ";
    }

    openJJTreeComment(io, node.node_scope.getNodeDescriptorText());
    io.println();
    insertOpenNodeCode(node.node_scope, io, indent);
    tryTokenSequence(node.node_scope, io, indent, first, node.getLastToken());
    return null;
  }

  public Object visit(ASTLHS node, Object data) {
    IO io = (IO) data;
    NodeScope ns = NodeScope.getEnclosingNodeScope(node);

    /*
     * Print out all the tokens, converting all references to `jjtThis' into the current node
     * variable.
     */
    Token first = node.getFirstToken();
    Token last = node.getLastToken();
    for (Token t = first; t != last.next; t = t.next) {
      TokenUtils.print(t, io, "jjtThis", ns.getNodeVariable());
    }

    return null;
  }

  /*
   * This method prints the tokens corresponding to this node recursively calling the print methods
   * of its children. Overriding this print method in appropriate nodes gives the output the added
   * stuff not in the input.
   */

  public Object visit(JJTreeNode node, Object data) {
    IO io = (IO) data;
    /*
     * Some productions do not consume any tokens. In that case their first and last tokens are a
     * bit strange.
     */
    if (node.getLastToken().next == node.getFirstToken()) {
      return null;
    }

    Token t1 = node.getFirstToken();
    Token t = new Token();
    t.next = t1;
    JJTreeNode n;
    for (int ord = 0; ord < node.jjtGetNumChildren(); ord++) {
      n = (JJTreeNode) node.jjtGetChild(ord);
      while (true) {
        t = t.next;
        if (t == n.getFirstToken())
          break;
        node.print(t, io);
      }
      n.jjtAccept(this, io);
      t = n.getLastToken();
    }
    while (t != node.getLastToken()) {
      t = t.next;
      node.print(t, io);
    }

    return null;
  }


  static void openJJTreeComment(IO io, String arg) {
    if(CodeGenerator.IS_DEBUG) 
    {
      if (arg != null) {
        io.print("/*@bgen(jjtree) " + arg + (Options.booleanValue("IGNORE_ACTIONS")  ? "" :" */"));
      } else {
        io.print("/*@bgen(jjtree)" + (Options.booleanValue("IGNORE_ACTIONS") ? "" : "*/"));
      }
    }
  }


  static void closeJJTreeComment(IO io) {
    if(CodeGenerator.IS_DEBUG) 
    {
      io.print((Options.booleanValue("IGNORE_ACTIONS") ? "" : "/*") + "@egen*/");
    }
  }


  String getIndentation(JJTreeNode n) {
    return getIndentation(n, 0);
  }


  String getIndentation(JJTreeNode n, int offset) {
    String s = "";
    for (int i = offset + 1; i < n.getFirstToken().beginColumn; ++i) {
      s += " ";
    }
    return s;
  }

  void insertOpenNodeDeclaration(NodeScope ns, IO io, String indent) {
    insertOpenNodeCode(ns, io, indent);
  }

  void insertOpenNodeCode(NodeScope ns, IO io, String indent) {
    String type = ns.node_descriptor.getNodeType();
    final String nodeClass;
    if (JJTreeOptions.getNodeClass().length() > 0 && !JJTreeOptions.getMulti()) {
      nodeClass = JJTreeOptions.getNodeClass();
    } else {
      nodeClass = type;
    }

    /*
     * Ensure that there is a template definition file for the node type.
     */
    NodeFiles.generateNodeType(type);

    io.print(indent + nodeClass + " *" + ns.nodeVar + " = ");
    String p = Options.getStatic() ? "null" : "this";
    String parserArg = JJTreeOptions.getNodeUsesParser() ? (p + ", ") : "";

    if (JJTreeOptions.getNodeFactory().equals("*")) {
      // Old-style multiple-implementations.
      io.println("(" + nodeClass + "*)" + nodeClass + "::jjtCreate(" + parserArg +
          ns.node_descriptor.getNodeId() +");");
    } else if (JJTreeOptions.getNodeFactory().length() > 0) {
      io.println("(" + nodeClass + "*)nodeFactory->jjtCreate(" + parserArg +
       ns.node_descriptor.getNodeId() +");");
    } else {
      io.println("new " + nodeClass + "(" + parserArg + ns.node_descriptor.getNodeId() + ");");
    }

    if (ns.usesCloseNodeVar()) {
      io.println(indent + "bool " + ns.closedVar + " = true;");
    }
    io.println(indent + ns.node_descriptor.openNode(ns.nodeVar));
    if (JJTreeOptions.getNodeScopeHook()) {
      io.println(indent + "jjtreeOpenNodeScope(" + ns.nodeVar + ");");
    }

    if (JJTreeOptions.getTrackTokens()) {
      io.println(indent + ns.nodeVar + ".jjtSetFirstToken(getToken(1));");
    }
  }


  void insertCloseNodeCode(NodeScope ns, IO io, String indent, boolean isFinal) {
    String closeNode = ns.node_descriptor.closeNode(ns.nodeVar);
    io.println(indent + closeNode);
    if (ns.usesCloseNodeVar() && !isFinal) {
      io.println(indent + ns.closedVar + " = false;");
    }
    if (JJTreeOptions.getNodeScopeHook()) {
      io.println(indent + "if (jjtree.nodeCreated()) {");
      io.println(indent + " jjtreeCloseNodeScope(" + ns.nodeVar + ");");
      io.println(indent + "}");
    }

    if (JJTreeOptions.getTrackTokens()) {
      io.println(indent + ns.nodeVar + ".jjtSetLastToken(getToken(0));");
    }
  }


  void insertOpenNodeAction(NodeScope ns, IO io, String indent) {
    io.println(indent + "{");
    insertOpenNodeCode(ns, io, indent + "  ");
    io.println(indent + "}");
  }


  void insertCloseNodeAction(NodeScope ns, IO io, String indent) {
    io.println(indent + "{");
    insertCloseNodeCode(ns, io, indent + "  ", false);
    io.println(indent + "}");
  }


  private void insertCatchBlocks(NodeScope ns, IO io, String indent) {
    io.println(indent + "} catch (...) {"); // " +  ns.exceptionVar + ") {");

    if (ns.usesCloseNodeVar()) {
      io.println(indent + "  if (" + ns.closedVar + ") {");
      io.println(indent + "    jjtree.clearNodeScope(" + ns.nodeVar + ");");
      io.println(indent + "    " + ns.closedVar + " = false;");
      io.println(indent + "  } else {");
      io.println(indent + "    jjtree.popNode();");
      io.println(indent + "  }");
    }

  }

  void tryTokenSequence(NodeScope ns, IO io, String indent, Token first, Token last) {
    io.println(indent + "try {");
    closeJJTreeComment(io);

    /*
     * Print out all the tokens, converting all references to `jjtThis' into the current node
     * variable.
     */
    for (Token t = first; t != last.next; t = t.next) {
      TokenUtils.print(t, io, "jjtThis", ns.nodeVar);
    }

    openJJTreeComment(io, null);
    io.println();

    insertCatchBlocks(ns, io, indent);

    io.println(indent + "} {");
    if (ns.usesCloseNodeVar()) {
      io.println(indent + "  if (" + ns.closedVar + ") {");
      insertCloseNodeCode(ns, io, indent + "    ", true);
      io.println(indent + "  }");
    }
    io.println(indent + "}");
    closeJJTreeComment(io);
  }


  void tryExpansionUnit(NodeScope ns, IO io, String indent, JJTreeNode expansion_unit) {
    io.println(indent + "try {");
    closeJJTreeComment(io);

    expansion_unit.jjtAccept(this, io);

    openJJTreeComment(io, null);
    io.println();

    insertCatchBlocks(ns, io, indent);

    io.println(indent + "} {");
    if (ns.usesCloseNodeVar()) {
      io.println(indent + "  if (" + ns.closedVar + ") {");
      insertCloseNodeCode(ns, io, indent + "    ", true);
      io.println(indent + "  }");
    }
    io.println(indent + "}");
    closeJJTreeComment(io);
  }

  @Override
  public void generateHelperFiles() throws java.io.IOException {
    Map<String, Object> options = Options.getOptions();
    options.put(Options.NONUSER_OPTION__PARSER_NAME, JJTreeGlobals.parserName);
    String filePrefix = new File(JJTreeOptions.getJJTreeOutputDirectory(), "JJT" + JJTreeGlobals.parserName + "State")
        .getAbsolutePath();

    PrintWriter pw = new OutputFile(new File(filePrefix + ".h"), JJTStateVersion, new String[0]).getPrintWriter();
    OutputFileGenerator generator = new OutputFileGenerator("/templates/cpp/JJTTreeState.h.template", options);
    generator.generate(pw);
    pw.close();

    pw = new OutputFile(new File(filePrefix + ".cc"), JJTStateVersion, new String[0]).getPrintWriter();
    generator = new OutputFileGenerator("/templates/cpp/JJTTreeState.cc.template", options);
    generator.generate(pw);
    pw.close();

    NodeFiles.generateOutputFiles();
  }
}
