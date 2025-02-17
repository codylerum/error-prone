/*
 * Copyright 2012 The Error Prone Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone.bugpatterns;

import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;
import static com.google.errorprone.bugpatterns.CheckReturnValue.MessageTrailerStyle.NONE;
import static com.google.errorprone.bugpatterns.checkreturnvalue.AutoValueRules.autoBuilders;
import static com.google.errorprone.bugpatterns.checkreturnvalue.AutoValueRules.autoValueBuilders;
import static com.google.errorprone.bugpatterns.checkreturnvalue.AutoValueRules.autoValues;
import static com.google.errorprone.bugpatterns.checkreturnvalue.ExternalCanIgnoreReturnValue.externalIgnoreList;
import static com.google.errorprone.bugpatterns.checkreturnvalue.ExternalCanIgnoreReturnValue.methodNameAndParams;
import static com.google.errorprone.bugpatterns.checkreturnvalue.ExternalCanIgnoreReturnValue.surroundingClass;
import static com.google.errorprone.bugpatterns.checkreturnvalue.ProtoRules.mutableProtos;
import static com.google.errorprone.bugpatterns.checkreturnvalue.ProtoRules.protoBuilders;
import static com.google.errorprone.bugpatterns.checkreturnvalue.ResultUsePolicy.EXPECTED;
import static com.google.errorprone.bugpatterns.checkreturnvalue.ResultUsePolicy.OPTIONAL;
import static com.google.errorprone.bugpatterns.checkreturnvalue.Rules.globalDefault;
import static com.google.errorprone.bugpatterns.checkreturnvalue.Rules.mapAnnotationSimpleName;
import static com.google.errorprone.fixes.SuggestedFix.emptyFix;
import static com.google.errorprone.util.ASTHelpers.getSymbol;
import static com.google.errorprone.util.ASTHelpers.getType;
import static com.google.errorprone.util.ASTHelpers.hasDirectAnnotationWithSimpleName;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.bugpatterns.BugChecker.ClassTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.MethodTreeMatcher;
import com.google.errorprone.bugpatterns.checkreturnvalue.PackagesRule;
import com.google.errorprone.bugpatterns.checkreturnvalue.ResultUsePolicy;
import com.google.errorprone.bugpatterns.checkreturnvalue.ResultUsePolicyEvaluator;
import com.google.errorprone.fixes.Fix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberReferenceTree.ReferenceMode;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.MethodType;
import java.util.Optional;
import javax.lang.model.element.ElementKind;

/**
 * @author eaftan@google.com (Eddie Aftandilian)
 */
@BugPattern(
    altNames = {"ResultOfMethodCallIgnored", "ReturnValueIgnored"},
    summary = "The result of this call must be used",
    severity = ERROR)
public class CheckReturnValue extends AbstractReturnValueIgnored
    implements MethodTreeMatcher, ClassTreeMatcher {

  private static final String CHECK_RETURN_VALUE = "CheckReturnValue";
  private static final String CAN_IGNORE_RETURN_VALUE = "CanIgnoreReturnValue";

  static final String CHECK_ALL_CONSTRUCTORS = "CheckReturnValue:CheckAllConstructors";
  static final String CHECK_ALL_METHODS = "CheckReturnValue:CheckAllMethods";

  static final String CRV_PACKAGES = "CheckReturnValue:Packages";

  private final MessageTrailerStyle messageTrailerStyle;
  private final Optional<ResultUsePolicy> constructorPolicy;
  private final Optional<ResultUsePolicy> methodPolicy;
  private final ResultUsePolicyEvaluator evaluator;

  public CheckReturnValue(ErrorProneFlags flags) {
    super(flags);
    this.messageTrailerStyle =
        flags
            .getEnum("CheckReturnValue:MessageTrailerStyle", MessageTrailerStyle.class)
            .orElse(NONE);
    this.constructorPolicy = defaultPolicy(flags, CHECK_ALL_CONSTRUCTORS);
    this.methodPolicy = defaultPolicy(flags, CHECK_ALL_METHODS);

    ResultUsePolicyEvaluator.Builder builder =
        ResultUsePolicyEvaluator.builder()
            .addRules(
                // The order of these rules matters somewhat because when checking a method, we'll
                // evaluate them in the order they're listed here and stop as soon as one of them
                // returns a result. The order shouldn't matter because most of these, with the
                // exception of perhaps the external ignore list, are equivalent in importance and
                // we should be checking declarations to ensure they aren't producing differing
                // results (i.e. ensuring an @AutoValue.Builder setter method isn't annotated @CRV).
                mapAnnotationSimpleName(CHECK_RETURN_VALUE, EXPECTED),
                mapAnnotationSimpleName(CAN_IGNORE_RETURN_VALUE, OPTIONAL),
                protoBuilders(),
                mutableProtos(),
                autoValues(),
                autoValueBuilders(),
                autoBuilders(),

                // This is conceptually lower precedence than the above rules.
                externalIgnoreList());
    flags
        .getList(CRV_PACKAGES)
        .ifPresent(packagePatterns -> builder.addRule(PackagesRule.fromPatterns(packagePatterns)));
    this.evaluator = builder.addRule(globalDefault(methodPolicy, constructorPolicy)).build();
  }

  private static Optional<ResultUsePolicy> defaultPolicy(ErrorProneFlags flags, String flag) {
    return flags.getBoolean(flag).map(check -> check ? EXPECTED : OPTIONAL);
  }

  /**
   * Return a matcher for method invocations in which the method being called should be considered
   * must-be-used.
   */
  @Override
  public Matcher<ExpressionTree> specializedMatcher() {
    return (tree, state) ->
        methodToInspect(tree)
            .map(method -> evaluator.evaluate(method, state))
            .orElse(OPTIONAL)
            .equals(EXPECTED);
  }

  private static Optional<MethodSymbol> methodToInspect(ExpressionTree tree) {
    // If we're in the middle of calling an anonymous class, we want to actually look at the
    // corresponding constructor of the supertype (e.g.: if I extend a class with a @CIRV
    // constructor that I delegate to, then my anonymous class's constructor should *also* be
    // considered @CIRV).
    if (tree instanceof NewClassTree) {
      ClassTree anonymousClazz = ((NewClassTree) tree).getClassBody();
      if (anonymousClazz != null) {
        // There should be a single defined constructor in the anonymous class body
        var constructor =
            anonymousClazz.getMembers().stream()
                .filter(MethodTree.class::isInstance)
                .map(MethodTree.class::cast)
                .filter(mt -> getSymbol(mt).isConstructor())
                .findFirst();

        // and its first statement should be a super() call to the method in question.
        return constructor
            .map(MethodTree::getBody)
            .map(block -> block.getStatements().get(0))
            .map(ExpressionStatementTree.class::cast)
            .map(ExpressionStatementTree::getExpression)
            .map(MethodInvocationTree.class::cast)
            .map(ASTHelpers::getSymbol);
      }
    }
    return methodSymbol(tree);
  }

  private static Optional<MethodSymbol> methodSymbol(ExpressionTree tree) {
    Symbol sym = ASTHelpers.getSymbol(tree);
    return sym instanceof MethodSymbol ? Optional.of((MethodSymbol) sym) : Optional.empty();
  }

  @Override
  public boolean isCovered(ExpressionTree tree, VisitorState state) {
    return methodToInspect(tree).stream()
        .flatMap(method -> evaluator.evaluations(method, state))
        .findFirst()
        .isPresent();
  }

  @Override
  public ImmutableMap<String, ?> getMatchMetadata(ExpressionTree tree, VisitorState state) {
    return methodToInspect(tree).stream()
        .flatMap(method -> evaluator.evaluations(method, state))
        .findFirst()
        .map(
            evaluation ->
                ImmutableMap.of(
                    "rule", evaluation.rule(),
                    "policy", evaluation.policy(),
                    "scope", evaluation.scope()))
        .orElse(ImmutableMap.of());
  }

  private static final String BOTH_ERROR =
      "@CheckReturnValue and @CanIgnoreReturnValue cannot both be applied to the same %s";

  /**
   * Validate {@code @CheckReturnValue} and {@link CanIgnoreReturnValue} usage on methods.
   *
   * <p>The annotations should not both be applied to the same method.
   *
   * <p>The annotations should not be applied to void-returning methods. Doing so makes no sense,
   * because there is no return value to check.
   */
  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    MethodSymbol method = ASTHelpers.getSymbol(tree);

    boolean checkReturn = hasDirectAnnotationWithSimpleName(method, CHECK_RETURN_VALUE);
    boolean canIgnore = hasDirectAnnotationWithSimpleName(method, CAN_IGNORE_RETURN_VALUE);

    // TODO(cgdecker): We can check this with evaluator.checkForConflicts now, though I want to
    //  think more about how we build and format error messages in that.
    if (checkReturn && canIgnore) {
      return buildDescription(tree).setMessage(String.format(BOTH_ERROR, "method")).build();
    }

    String annotationToValidate;
    if (checkReturn) {
      annotationToValidate = CHECK_RETURN_VALUE;
    } else if (canIgnore) {
      annotationToValidate = CAN_IGNORE_RETURN_VALUE;
    } else {
      return Description.NO_MATCH;
    }
    if (method.getKind() != ElementKind.METHOD) {
      // skip constructors (which javac thinks are void-returning)
      return Description.NO_MATCH;
    }
    if (!ASTHelpers.isVoidType(method.getReturnType(), state)) {
      return Description.NO_MATCH;
    }
    String message =
        String.format("@%s may not be applied to void-returning methods", annotationToValidate);
    return buildDescription(tree).setMessage(message).build();
  }

  /**
   * Validate that at most one of {@code CheckReturnValue} and {@code CanIgnoreReturnValue} are
   * applied to a class (or interface or enum).
   */
  @Override
  public Description matchClass(ClassTree tree, VisitorState state) {
    if (hasDirectAnnotationWithSimpleName(ASTHelpers.getSymbol(tree), CHECK_RETURN_VALUE)
        && hasDirectAnnotationWithSimpleName(ASTHelpers.getSymbol(tree), CAN_IGNORE_RETURN_VALUE)) {
      return buildDescription(tree).setMessage(String.format(BOTH_ERROR, "class")).build();
    }
    return Description.NO_MATCH;
  }

  private Description describeInvocationResultIgnored(
      Tree tree,
      String shortCall,
      String shortCallWithoutNew,
      MethodSymbol symbol,
      Fix fix,
      VisitorState state) {
    String message =
        String.format(
            "The result of `%s` must be used\n"
                + "If you really don't want to use the result, then assign it to a variable:"
                + " `var unused = ...`.\n"
                + "\n"
                + "If callers of `%s` shouldn't be required to use its result,"
                + " then annotate it with `@CanIgnoreReturnValue`.\n"
                + "%s",
            shortCall, shortCallWithoutNew, apiTrailer(symbol, state));
    return buildDescription(tree).addFix(fix).setMessage(message).build();
  }

  @Override
  protected Description describeReturnValueIgnored(MethodInvocationTree tree, VisitorState state) {
    MethodSymbol symbol = getSymbol(tree);
    String shortCall = symbol.name + (tree.getArguments().isEmpty() ? "()" : "(...)");
    String shortCallWithoutNew = shortCall;
    return describeInvocationResultIgnored(
        tree, shortCall, shortCallWithoutNew, symbol, makeFix(tree, state), state);
  }

  @Override
  protected Description describeReturnValueIgnored(NewClassTree tree, VisitorState state) {
    MethodSymbol symbol = getSymbol(tree);
    String shortCallWithoutNew =
        state.getSourceForNode(tree.getIdentifier())
            + (tree.getArguments().isEmpty() ? "()" : "(...)");
    String shortCall = "new " + shortCallWithoutNew;
    return describeInvocationResultIgnored(
        tree, shortCall, shortCallWithoutNew, symbol, emptyFix(), state);
  }

  @Override
  protected Description describeReturnValueIgnored(MemberReferenceTree tree, VisitorState state) {
    MethodSymbol symbol = getSymbol(tree);
    Type type = state.getTypes().memberType(getType(tree.getQualifierExpression()), symbol);
    // TODO(cgdecker): There are probably other types than MethodType that we could resolve here
    String parensAndMaybeEllipsis =
        type instanceof MethodType && ((MethodType) type).getParameterTypes().isEmpty()
            ? "()"
            : "(...)";

    String shortCallWithoutNew;
    String shortCall;
    if (tree.getMode() == ReferenceMode.NEW) {
      shortCallWithoutNew =
          state.getSourceForNode(tree.getQualifierExpression()) + parensAndMaybeEllipsis;
      shortCall = "new " + shortCallWithoutNew;
    } else {
      shortCallWithoutNew = tree.getName() + parensAndMaybeEllipsis;
      shortCall = shortCallWithoutNew;
    }

    String implementedMethod =
        getType(tree).asElement().getSimpleName()
            + "."
            + state.getTypes().findDescriptorSymbol(getType(tree).asElement()).getSimpleName();
    String methodReference = state.getSourceForNode(tree);
    String message =
        String.format(
            "The result of `%s` must be used\n"
                + "`%s` acts as an implementation of `%s`.\n"
                + "— which is a `void` method, so it doesn't use the result of `%s`.\n"
                + "\n"
                + "To use the result, you may need to restructure your code.\n"
                + "\n"
                + "If you really don't want to use the result, then switch to a lambda that assigns"
                + " it to a variable: `%s -> { var unused = ...; }`.\n"
                + "\n"
                + "If callers of `%s` shouldn't be required to use its result,"
                + " then annotate it with `@CanIgnoreReturnValue`.\n"
                + "%s",
            shortCall,
            methodReference,
            implementedMethod,
            shortCall,
            parensAndMaybeEllipsis,
            shortCallWithoutNew,
            apiTrailer(symbol, state));
    return buildDescription(tree).setMessage(message).build();
  }

  private String apiTrailer(MethodSymbol symbol, VisitorState state) {
    if (symbol.enclClass().isAnonymous()) {
      // I don't think we have a defined format for members of anonymous classes.
      return "";
    }
    switch (messageTrailerStyle) {
      case NONE:
        return "";
      case API_ERASED_SIGNATURE:
        return "\n\nFull API: "
            + surroundingClass(symbol)
            + "#"
            + methodNameAndParams(symbol, state.getTypes());
    }
    throw new AssertionError();
  }

  enum MessageTrailerStyle {
    NONE,
    API_ERASED_SIGNATURE,
  }
}
