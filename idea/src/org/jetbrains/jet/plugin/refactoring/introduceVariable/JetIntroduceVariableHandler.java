package org.jetbrains.jet.plugin.refactoring.introduceVariable;

import com.intellij.codeInsight.PsiEquivalenceUtil;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pass;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.HelpID;
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.java.AnalyzerFacade;
import org.jetbrains.jet.lang.types.JetStandardLibrary;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.NamespaceType;
import org.jetbrains.jet.lang.types.checker.JetTypeChecker;
import org.jetbrains.jet.lexer.JetTokens;
import org.jetbrains.jet.plugin.refactoring.*;

import java.util.*;

/**
 * User: Alefas
 * Date: 25.01.12
 */
public class JetIntroduceVariableHandler extends JetIntroduceHandlerBase {

    private static final String INTRODUCE_VARIABLE = JetRefactoringBundle.message("introduce.variable");

    @Override
    public void invoke(@NotNull final Project project, final Editor editor, final PsiFile file, DataContext dataContext) {
        JetRefactoringUtil.SelectExpressionCallback callback = new JetRefactoringUtil.SelectExpressionCallback() {
            @Override
            public void run(@Nullable JetExpression expression) {
                doRefactoring(project, editor, expression);
            }
        };
        try {
            JetRefactoringUtil.selectExpression(editor, file, callback);
        } catch (JetRefactoringUtil.IntroduceRefactoringException e) {
            showErrorHint(project, editor, e.getMessage());
        }
    }

    private static void doRefactoring(@NotNull final Project project, final Editor editor, @Nullable JetExpression _expression) {
        if (_expression == null) {
            showErrorHint(project, editor, JetRefactoringBundle.message("cannot.refactor.no.expression"));
            return;
        }
        if (_expression.getParent() instanceof JetParenthesizedExpression) {
            _expression = (JetExpression) _expression.getParent();
        }
        final JetExpression expression = _expression;
        if (expression.getParent() instanceof JetQualifiedExpression) {
            JetQualifiedExpression qualifiedExpression = (JetQualifiedExpression) expression.getParent();
            if (qualifiedExpression.getReceiverExpression() != expression) {
                showErrorHint(project, editor, JetRefactoringBundle.message("cannot.refactor.no.expression"));
                return;
            }
        } else if (expression.getParent() instanceof JetCallElement || expression instanceof JetStatementExpression) {
            showErrorHint(project, editor, JetRefactoringBundle.message("cannot.refactor.no.expression"));
            return;
        } else if (expression.getParent() instanceof JetOperationExpression) {
            JetOperationExpression operationExpression = (JetOperationExpression) expression.getParent();
            if (operationExpression.getOperationReference() == expression) {
                showErrorHint(project, editor, JetRefactoringBundle.message("cannot.refactor.no.expression"));
                return;
            }
        }
        BindingContext bindingContext = AnalyzerFacade.analyzeFileWithCache((JetFile) expression.getContainingFile(),
                                                                            AnalyzerFacade.SINGLE_DECLARATION_PROVIDER);
        JetType expressionType = bindingContext.get(BindingContext.EXPRESSION_TYPE, expression); //can be null or error type
        if (expressionType instanceof NamespaceType) {
            showErrorHint(project, editor, JetRefactoringBundle.message("cannot.refactor.namespace.expression"));
            return;
        } if (expressionType != null &&
              JetTypeChecker.INSTANCE.equalTypes(JetStandardLibrary.getJetStandardLibrary(project).getTuple0Type(), expressionType)) {
            showErrorHint(project, editor, JetRefactoringBundle.message("cannot.refactor.expression.has.unit.type"));
            return;
        }
        final PsiElement container = getContainer(expression);
        final PsiElement occurrenceContainer = getOccurrenceContainer(expression);
        if (container == null) {
            showErrorHint(project, editor, JetRefactoringBundle.message("cannot.refactor.no.container"));
            return;
        }
        final boolean isInplaceAvailableOnDataContext =
                editor.getSettings().isVariableInplaceRenameEnabled() &&
                !ApplicationManager.getApplication().isUnitTestMode();
        final ArrayList<JetExpression> allOccurrences = findOccurrences(occurrenceContainer, expression);
        Pass<OccurrencesChooser.ReplaceChoice> callback = new Pass<OccurrencesChooser.ReplaceChoice>() {
            @Override
            public void pass(OccurrencesChooser.ReplaceChoice replaceChoice) {
                boolean replaceOccurrence = container != expression.getParent();
                final List<JetExpression> allReplaces;
                if (OccurrencesChooser.ReplaceChoice.ALL == replaceChoice) {
                    if (allOccurrences.size() > 1) replaceOccurrence = true;
                    allReplaces = allOccurrences;
                } else {
                    allReplaces = Collections.singletonList(expression);
                }
                
                String[] suggestedNames = JetNameSuggester.suggestNames(expression, new JetNameValidatorImpl(expression));
                final LinkedHashSet<String> suggestedNamesSet = new LinkedHashSet<String>();
                Collections.addAll(suggestedNamesSet, suggestedNames);
                PsiElement commonParent = PsiTreeUtil.findCommonParent(allReplaces);
                PsiElement commonContainer = getContainer(commonParent);
                final Ref<JetProperty> propertyRef = new Ref<JetProperty>();
                final ArrayList<JetExpression> references = new ArrayList<JetExpression>();
                final Ref<JetExpression> reference = new Ref<JetExpression>();
                final Runnable introduceRunnable = introduceVariable(project, expression, suggestedNames, allReplaces, commonContainer,
                                                               commonParent, replaceOccurrence, propertyRef, references,
                                                               reference);
                final boolean finalReplaceOccurrence = replaceOccurrence;
                CommandProcessor.getInstance().executeCommand(project, new Runnable() {
                    @Override
                    public void run() {
                        ApplicationManager.getApplication().runWriteAction(introduceRunnable);
                        JetProperty property = propertyRef.get();
                        if (property != null) {
                            editor.getCaretModel().moveToOffset(property.getTextOffset());
                            editor.getSelectionModel().removeSelection();
                            if (isInplaceAvailableOnDataContext) {
                                PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
                                PsiDocumentManager.getInstance(project).
                                        doPostponedOperationsAndUnblockDocument(editor.getDocument());
                                JetInplaceVariableIntroducer variableIntroducer =
                                        new JetInplaceVariableIntroducer(property, editor, project, INTRODUCE_VARIABLE,
                                                                         references.toArray(new JetExpression[references.size()]),
                                                                         reference.get(), finalReplaceOccurrence,
                                                                         property);
                                variableIntroducer.performInplaceRefactoring(suggestedNamesSet);
                            }
                        }
                    }
                }, INTRODUCE_VARIABLE, null);
            }
        };
        if (isInplaceAvailableOnDataContext) {
            OccurrencesChooser.<JetExpression>simpleChooser(editor).
                    showChooser(expression, allOccurrences, callback);
        } else {
            callback.pass(OccurrencesChooser.ReplaceChoice.ALL);
        }
    }
    
    private static Runnable introduceVariable(final @NotNull Project project, final JetExpression expression, 
                                              final String[] suggestedNames,
                                              final List<JetExpression> allReplaces, final PsiElement commonContainer,
                                              final PsiElement commonParent, final boolean replaceOccurrence,
                                              final Ref<JetProperty> propertyRef,
                                              final ArrayList<JetExpression> references, 
                                              final Ref<JetExpression> reference) {
       return new Runnable() {
           @Override
           public void run() {
               String variableText = "val " + suggestedNames[0] + " = ";
               if (expression instanceof JetParenthesizedExpression) {
                   JetParenthesizedExpression parenthesizedExpression = (JetParenthesizedExpression) expression;
                   JetExpression innerExpression = parenthesizedExpression.getExpression();
                   if (innerExpression != null) variableText += innerExpression.getText();
                   else variableText += expression.getText();
               } else variableText += expression.getText();
               JetProperty property = JetPsiFactory.createProperty(project, variableText);
               if (property == null) return;
               PsiElement anchor = commonParent;
               if (anchor != commonContainer) {
                   while (anchor.getParent() != commonContainer) {
                       anchor = anchor.getParent();
                   }
               } else {
                   anchor = commonContainer.getFirstChild();
                   int startOffset = commonContainer.getTextRange().getEndOffset();
                   for (JetExpression expr : allReplaces) {
                       int offset = expr.getTextRange().getStartOffset();
                       if (offset < startOffset) startOffset = offset;
                   }
                   while (anchor != null && !anchor.getTextRange().contains(startOffset)) {
                       anchor = anchor.getNextSibling();
                   }
                   if (anchor == null) return;
               }
               boolean needBraces = !(commonContainer instanceof JetBlockExpression ||
                           commonContainer instanceof JetClassBody ||
                           commonContainer instanceof JetClassInitializer);
               if (!needBraces) {
                   property = (JetProperty) commonContainer.addBefore(property, anchor);
                   commonContainer.addBefore(JetPsiFactory.createWhiteSpace(project, "\n"), anchor);
               } else {
                   JetExpression emptyBody = JetPsiFactory.createEmptyBody(project);
                   PsiElement firstChild = emptyBody.getFirstChild();
                   emptyBody.addAfter(JetPsiFactory.createWhiteSpace(project, "\n"), firstChild);
                   property = (JetProperty) emptyBody.addAfter(property, firstChild);
                   emptyBody.addAfter(JetPsiFactory.createWhiteSpace(project, "\n"), firstChild);
                   emptyBody = (JetExpression) anchor.replace(emptyBody);
                   for (PsiElement child : emptyBody.getChildren()) {
                       if (child instanceof JetProperty) {
                           property = (JetProperty) child;
                       }
                   }
                   if (commonContainer instanceof JetNamedFunction) {
                       //we should remove equals sign
                       JetNamedFunction function = (JetNamedFunction) commonContainer;
                       if (!function.hasDeclaredReturnType()) {
                           //todo: add return type
                       }
                       function.getEqualsToken().delete();
                   } else if (commonContainer instanceof  JetContainerNode) {
                       JetContainerNode node = (JetContainerNode) commonContainer;
                       if (node.getParent() instanceof JetIfExpression) {
                           PsiElement next = node.getNextSibling();
                           if (next != null) {
                               PsiElement nextnext = next.getNextSibling();
                               if (nextnext != null && nextnext.getNode().getElementType() == JetTokens.ELSE_KEYWORD) {
                                   if (next instanceof PsiWhiteSpace) {
                                       next.replace(JetPsiFactory.createWhiteSpace(project, " ")) ;
                                   }
                               }
                           }
                       }
                   }
               }
               for (JetExpression replace : allReplaces) {
                   if (replaceOccurrence) {
                       boolean isActualExpression = expression == replace;
                       JetExpression element = (JetExpression) replace.replace(JetPsiFactory.createExpression(project, suggestedNames[0]));
                       references.add(element);
                       if (isActualExpression) reference.set(element);
                   } else if (!needBraces) {
                       replace.delete();
                   }
               }
               propertyRef.set(property);
           }
       };
    }
    
    private static ArrayList<JetExpression> findOccurrences(PsiElement occurrenceContainer, @NotNull JetExpression expression) {
        if (expression instanceof JetParenthesizedExpression) {
            JetParenthesizedExpression parenthesizedExpression = (JetParenthesizedExpression) expression;
            JetExpression innerExpression = parenthesizedExpression.getExpression();
            if (innerExpression != null) {
                expression = innerExpression;
            }
        }
        final JetExpression actualExpression = expression;

        final ArrayList<JetExpression> result = new ArrayList<JetExpression>();

        final BindingContext bindingContext = AnalyzerFacade.analyzeFileWithCache((JetFile) expression.getContainingFile(),
                                                                            AnalyzerFacade.SINGLE_DECLARATION_PROVIDER);

        JetVisitorVoid visitor = new JetVisitorVoid() {
            @Override
            public void visitJetElement(JetElement element) {
                element.acceptChildren(this);
                super.visitJetElement(element);
            }

            @Override
            public void visitExpression(final JetExpression expression) {
                if (PsiEquivalenceUtil.areElementsEquivalent(expression, actualExpression, null, new Comparator<PsiElement>() {
                    @Override
                    public int compare(PsiElement element1, PsiElement element2) {
                        if (element1.getNode().getElementType() == JetTokens.IDENTIFIER &&
                            element2.getNode().getElementType() == JetTokens.IDENTIFIER) {
                            if (element1.getParent() instanceof JetSimpleNameExpression &&
                                element2.getParent() instanceof  JetSimpleNameExpression) {
                                JetSimpleNameExpression expr1 = (JetSimpleNameExpression) element1.getParent();
                                JetSimpleNameExpression expr2 = (JetSimpleNameExpression) element2.getParent();
                                DeclarationDescriptor descr1 = bindingContext.get(BindingContext.REFERENCE_TARGET, expr1);
                                DeclarationDescriptor descr2 = bindingContext.get(BindingContext.REFERENCE_TARGET, expr2);
                                if (descr1 != descr2) return 1;
                                else return 0;
                            }
                        }
                        if (!element1.textMatches(element2)) return 1;
                        else return 0;
                    }
                }, null, false)) {
                    PsiElement parent = expression.getParent();
                    if (parent instanceof JetParenthesizedExpression) {
                        result.add((JetParenthesizedExpression) parent);
                    } else {
                        result.add(expression);
                    }
                } else {
                    super.visitExpression(expression);
                }
            }
        };
        occurrenceContainer.accept(visitor);
        return result;
    }

    @Nullable
    private static PsiElement getContainer(PsiElement place) {
        if (place instanceof JetBlockExpression || place instanceof JetClassBody ||
                place instanceof JetClassInitializer) {
            return place;
        }
        while (place != null) {
            PsiElement parent = place.getParent();
            if (parent instanceof JetContainerNode) {
                if (!isBadContainerNode((JetContainerNode) parent, place)) {
                    return parent;
                }
            } if (parent instanceof JetBlockExpression || parent instanceof JetWhenEntry ||
                parent instanceof JetClassBody || parent instanceof JetClassInitializer) {
                return parent;
            } else if (parent instanceof JetNamedFunction) {
                JetNamedFunction function = (JetNamedFunction) parent;
                if (function.getBodyExpression() == place) {
                    return parent;
                }
            } else if (parent instanceof JetSecondaryConstructor) {
                JetSecondaryConstructor secondaryConstructor = (JetSecondaryConstructor) parent;
                if (secondaryConstructor.getBodyExpression() == place) {
                    return parent;
                }
            }
            place = parent;
        }
        return null;
    }
    
    private static boolean isBadContainerNode(JetContainerNode parent, PsiElement place) {
        if (parent.getParent() instanceof JetIfExpression &&
            ((JetIfExpression) parent.getParent()).getCondition() == place) {
            return true;
        } else if (parent.getParent() instanceof JetLoopExpression &&
                   ((JetLoopExpression) parent.getParent()).getBody() != place) {
            return true;
        }
        return false;
    }

    @Nullable
    private static PsiElement getOccurrenceContainer(PsiElement place) {
        PsiElement result = null;
        while (place != null) {
            PsiElement parent = place.getParent();
            if (parent instanceof JetContainerNode) {
                if (!(place instanceof JetBlockExpression) && !isBadContainerNode((JetContainerNode) parent, place)) {
                    result = parent;
                }
            } else if (parent instanceof JetClassBody || parent instanceof JetFile || parent instanceof JetClassInitializer) {
                if (result == null) return parent;
                else return result;
            } else if (parent instanceof JetBlockExpression) {
                result = parent;
            } else if (parent instanceof JetWhenEntry ) {
                if (!(place instanceof JetBlockExpression)) {
                    result = parent;
                }
            } else if (parent instanceof JetNamedFunction) {
                JetNamedFunction function = (JetNamedFunction) parent;
                if (function.getBodyExpression() == place) {
                    if (!(place instanceof JetBlockExpression)) {
                        result = parent;
                    }
                }
            } else if (parent instanceof JetSecondaryConstructor) {
                JetSecondaryConstructor secondaryConstructor = (JetSecondaryConstructor) parent;
                if (secondaryConstructor.getBodyExpression() == place) {
                    if (!(place instanceof JetBlockExpression)) {
                        result = parent;
                    }
                }
            }
            place = parent;
        }
        return null;
    }

    private static void showErrorHint(Project project, Editor editor, String message) {
        if (ApplicationManager.getApplication().isUnitTestMode()) throw new RuntimeException(message);
        CommonRefactoringUtil.showErrorHint(project, editor, message,
                                            INTRODUCE_VARIABLE,
                                            HelpID.INTRODUCE_VARIABLE);
    }

    @Override
    public void invoke(@NotNull Project project, @NotNull PsiElement[] elements, DataContext dataContext) {
        //do nothing
    }
}
