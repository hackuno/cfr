package org.benf.cfr.reader.bytecode.analysis.parse.utils.scope;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.StatementContainer;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.LocalVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.SentinelLocalClassLValue;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.StackSSALabel;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.LValueAssignmentCollector;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.LValueUsageCollector;
import org.benf.cfr.reader.bytecode.analysis.structured.StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.structured.statement.Block;
import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.bytecode.analysis.types.MethodPrototype;
import org.benf.cfr.reader.bytecode.analysis.variables.NamedVariable;
import org.benf.cfr.reader.bytecode.analysis.variables.NamedVariableDefault;
import org.benf.cfr.reader.bytecode.analysis.variables.VariableFactory;
import org.benf.cfr.reader.util.*;
import org.benf.cfr.reader.util.functors.UnaryFunction;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: lee
 * Date: 06/03/2013
 * Time: 06:09
 */
public class LValueScopeDiscoverer implements LValueAssignmentCollector<StructuredStatement>, LValueUsageCollector {

    /*
     * We keep track of the first definition for a given variable.  If we exit the scope that the variable
     * is defined at (i.e. scope depth goes above) we have to remove all earliest definitions at that level.
     */
    private final Map<NamedVariable, ScopeDefinition> earliestDefinition = MapFactory.newIdentityMap();
    private final Map<Integer, Map<NamedVariable, Boolean>> earliestDefinitionsByLevel = MapFactory.newLazyMap(new UnaryFunction<Integer, Map<NamedVariable, Boolean>>() {
        @Override
        public Map<NamedVariable, Boolean> invoke(Integer arg) {
            return MapFactory.newIdentityMap();
        }
    });
    private transient int currentDepth = 0;
    private transient Stack<StatementContainer<StructuredStatement>> currentBlock = new Stack<StatementContainer<StructuredStatement>>();

    private final List<ScopeDefinition> discoveredCreations = ListFactory.newList();
    private final VariableFactory variableFactory;

    public LValueScopeDiscoverer(MethodPrototype prototype, VariableFactory variableFactory) {
        final List<LocalVariable> parameters = prototype.getComputedParameters();
        this.variableFactory = variableFactory;
        for (LocalVariable parameter : parameters) {
            JavaTypeInstance type = parameter.getInferredJavaType().getJavaTypeInstance();
            final ScopeDefinition prototypeScope = new ScopeDefinition(0, null, null, parameter, type, parameter.getName());
            earliestDefinition.put(parameter.getName(), prototypeScope);
        }
    }

    public void enterBlock(StructuredStatement structuredStatement) {
        StatementContainer<StructuredStatement> container = structuredStatement.getContainer();
        currentBlock.push(container);
        currentDepth++;
    }

    public void leaveBlock(StructuredStatement structuredStatement) {
        for (NamedVariable definedHere : earliestDefinitionsByLevel.get(currentDepth).keySet()) {
            earliestDefinition.remove(definedHere);
        }
        earliestDefinitionsByLevel.remove(currentDepth);
        StatementContainer<StructuredStatement> oldContainer = currentBlock.pop();
        if (structuredStatement.getContainer() != oldContainer) {
            throw new IllegalStateException();
        }
        currentDepth--;
    }

    @Override
    public void collect(StackSSALabel lValue, StatementContainer<StructuredStatement> statementContainer, Expression value) {

    }

    @Override
    public void collectMultiUse(StackSSALabel lValue, StatementContainer<StructuredStatement> statementContainer, Expression value) {

    }

    @Override
    public void collectMutatedLValue(LValue lValue, StatementContainer<StructuredStatement> statementContainer, Expression value) {

    }

    @Override
    public void collectLocalVariableAssignment(LocalVariable localVariable, StatementContainer<StructuredStatement> statementContainer, Expression value) {
        // Note that just because two local variables in the same scope have the same name, they're not NECESSARILY
        // the same variable - if we've reused a stack location, and don't have any naming hints, the name will have
        // been re-used.  This is why we also have to verify that the type of the new assignment is the same as the type
        // of the previous one, and kick out the previous (and remove from earlier scopes) if that's the case).
        NamedVariable name = localVariable.getName();
        ScopeDefinition previousDef = earliestDefinition.get(name);
        if (previousDef == null) {
            // First use is here.
            JavaTypeInstance type = localVariable.getInferredJavaType().getJavaTypeInstance();
            ScopeDefinition scopeDefinition = new ScopeDefinition(currentDepth, currentBlock, statementContainer, localVariable, type, name);
            earliestDefinition.put(name, scopeDefinition);
            earliestDefinitionsByLevel.get(currentDepth).put(name, true);
            discoveredCreations.add(scopeDefinition);
            return;
        }

        /*
         * Else verify type.
         */
        JavaTypeInstance oldType = previousDef.getJavaTypeInstance();
        JavaTypeInstance newType = localVariable.getInferredJavaType().getJavaTypeInstance();
        if (!oldType.equals(newType)) {
            earliestDefinitionsByLevel.get(previousDef.getDepth()).remove(previousDef.getName());
            if (previousDef.getDepth() == currentDepth) {
                variableFactory.mutatingRenameUnClash(localVariable);
                name = localVariable.getName();
            }

            JavaTypeInstance type = localVariable.getInferredJavaType().getJavaTypeInstance();
            ScopeDefinition scopeDefinition = new ScopeDefinition(currentDepth, currentBlock, statementContainer, localVariable, type, name);
            earliestDefinition.put(name, scopeDefinition);
            earliestDefinitionsByLevel.get(currentDepth).put(name, true);
            discoveredCreations.add(scopeDefinition);
        }
    }

    private static class ScopeKey {
        private final LValue lValue;
        private final JavaTypeInstance type;

        private ScopeKey(LValue lValue, JavaTypeInstance type) {
            this.lValue = lValue;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ScopeKey scopeKey = (ScopeKey) o;

            if (!lValue.equals(scopeKey.lValue)) return false;
            if (!type.equals(scopeKey.type)) return false;

            return true;
        }

        private LValue getlValue() {
            return lValue;
        }

        @Override
        public int hashCode() {
            int result = lValue.hashCode();
            result = 31 * result + type.hashCode();
            return result;
        }
    }

    public void markDiscoveredCreations() {
        /*
         * Eliminate enclosing scopes where they were falsely detected, and
         * where scopes for the same variable exist, lift to the lowest common denominator.
         */
        Map<ScopeKey, List<ScopeDefinition>> definitionsByType = Functional.groupToMapBy(discoveredCreations, new UnaryFunction<ScopeDefinition, ScopeKey>() {
            @Override
            public ScopeKey invoke(ScopeDefinition arg) {
                return arg.getScopeKey();
            }
        });

        List<ScopeDefinition> finalDefinitions = ListFactory.newList();
        for (Map.Entry<ScopeKey, List<ScopeDefinition>> entry : definitionsByType.entrySet()) {
            ScopeKey scopeKey = entry.getKey();
            List<ScopeDefinition> definitions = entry.getValue();
            // find the longest common nested scope - null wins automatically!

            List<StatementContainer<StructuredStatement>> commonScope = null;
            ScopeDefinition bestDefn = null;
            LValue scopedEntity = scopeKey.getlValue();
            for (ScopeDefinition definition : definitions) {
                StructuredStatement statement = definition.getStatementContainer().getStatement();

                if (statement.alwaysDefines(scopedEntity)) {
                    statement.markCreator(scopedEntity);
                    continue;
                }
                List<StatementContainer<StructuredStatement>> scopeList = definition.getNestedScope();
                if (scopeList == null) {
                    commonScope = null;
                    bestDefn = definition;
                    break;
                }
                if (commonScope == null) {
                    commonScope = scopeList;
                    bestDefn = definition;
                    continue;
                }
                // Otherwise, take the common prefix.
                commonScope = getCommonPrefix(commonScope, scopeList);
                if (commonScope.size() == scopeList.size()) {
                    bestDefn = definition;
                } else {
                    bestDefn = null;
                }
            }
            StatementContainer<StructuredStatement> creationContainer = null;
            if (scopedEntity instanceof SentinelLocalClassLValue) {
                List<StatementContainer<StructuredStatement>> scope = null;
                if (bestDefn != null) {
                    scope = bestDefn.getNestedScope();
                } else if (commonScope != null) {
                    scope = commonScope;
                }

                if (scope != null) {
                    for (int i = scope.size() - 1; i >= 0; --i) {
                        StatementContainer<StructuredStatement> thisItem = scope.get(i);
                        if (thisItem.getStatement() instanceof Block) {
                            Block block = (Block) thisItem.getStatement();
                            block.setIndenting(true);
                            creationContainer = thisItem;
                            break;
                        }
                    }
                }
            } else {
                if (bestDefn != null) {
                    creationContainer = bestDefn.getStatementContainer();
                } else if (commonScope != null) {
                    creationContainer = commonScope.get(commonScope.size() - 1);
                }
            }

            if (creationContainer != null) {
                creationContainer.getStatement().markCreator(scopedEntity);
            }
        }
    }


    private static <T> List<T> getCommonPrefix(List<T> a, List<T> b) {
        List<T> la, lb;
        if (a.size() < b.size()) {
            la = a;
            lb = b;
        } else {
            la = b;
            lb = a;
        }
        // la is shortest or equal.
        int maxRes = Math.min(la.size(), lb.size());
        int sameLen = 0;
        for (int x = 0; x < maxRes; ++x, ++sameLen) {
            if (!la.get(x).equals(lb.get(x))) break;
        }
        if (sameLen == la.size()) return la;
        return la.subList(0, sameLen);
    }
    /*
     *
     */

    @Override
    public void collect(LValue lValue) {
        Class<?> lValueClass = lValue.getClass();

        if (lValueClass == LocalVariable.class) {
            LocalVariable localVariable = (LocalVariable) lValue;
            NamedVariable name = localVariable.getName();
            if (name.getStringName().equals(MiscConstants.THIS)) return;

            ScopeDefinition previousDef = earliestDefinition.get(name);
            // If it's in scope, no problem.
            if (previousDef != null) return;

            // If it's out of scope, we have a variable defined but only assigned in an inner scope, but used in the
            // outer scope later!
            JavaTypeInstance type = lValue.getInferredJavaType().getJavaTypeInstance();
            ScopeDefinition scopeDefinition = new ScopeDefinition(currentDepth, currentBlock, currentBlock.peek(), lValue, type, name);
            earliestDefinition.put(name, scopeDefinition);
            earliestDefinitionsByLevel.get(currentDepth).put(name, true);
            discoveredCreations.add(scopeDefinition);
        } else if (lValueClass == SentinelLocalClassLValue.class) {
            SentinelLocalClassLValue localClassLValue = (SentinelLocalClassLValue) lValue;

            NamedVariable name = new SentinelNV(localClassLValue.getLocalClassType());

            ScopeDefinition previousDef = earliestDefinition.get(name);
            // If it's in scope, no problem.
            if (previousDef != null) return;

            JavaTypeInstance type = localClassLValue.getLocalClassType();
            ScopeDefinition scopeDefinition = new ScopeDefinition(currentDepth, currentBlock, currentBlock.peek(), lValue, type, name);
            earliestDefinition.put(name, scopeDefinition);
            earliestDefinitionsByLevel.get(currentDepth).put(name, true);
            discoveredCreations.add(scopeDefinition);

        }
    }

    private static class SentinelNV implements NamedVariable {
        private final JavaTypeInstance typeInstance;

        private SentinelNV(JavaTypeInstance typeInstance) {
            this.typeInstance = typeInstance;
        }

        @Override
        public void forceName(String name) {
        }

        @Override
        public String getStringName() {
            return typeInstance.getRawName();
        }

        @Override
        public boolean isGoodName() {
            return true;
        }

        @Override
        public Dumper dump(Dumper d) {
            return null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SentinelNV that = (SentinelNV) o;

            if (typeInstance != null ? !typeInstance.equals(that.typeInstance) : that.typeInstance != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return typeInstance != null ? typeInstance.hashCode() : 0;
        }
    }

//    /*
//     *
//     */
//    private interface ScopeDefinition {
//        public int getDepth();
//        public StatementContainer<StructuredStatement> getStatementContainer();
//        public NamedVariable getName();
//        public ScopeKey getScopeKey();
//        public List<StatementContainer<StructuredStatement>> getNestedScope();
//        public JavaTypeInstance getJavaTypeInstance();
//    }

    private static class ScopeDefinition {
        private final int depth;
        // Keeping this nested scope is woefully inefficient.... fixme.
        private final List<StatementContainer<StructuredStatement>> nestedScope;
        private final StatementContainer<StructuredStatement> exactStatement;
        private final LValue lValue;
        private final JavaTypeInstance lValueType;
        private final NamedVariable name;
        private final ScopeKey scopeKey;

        private ScopeDefinition(int depth, Stack<StatementContainer<StructuredStatement>> nestedScope, StatementContainer<StructuredStatement> exactStatement, LValue lValue, JavaTypeInstance type, NamedVariable name) {
            this.depth = depth;
            this.nestedScope = nestedScope == null ? null : ListFactory.newList(nestedScope);
            if (exactStatement == null && depth > 1) {
                int x = 1;
            }
            this.exactStatement = exactStatement;
            this.lValue = lValue;
            this.lValueType = type;
            this.name = name;
            this.scopeKey = new ScopeKey(lValue, type);
        }

        public JavaTypeInstance getJavaTypeInstance() {
            return lValueType;
        }

        public StatementContainer<StructuredStatement> getStatementContainer() {
            return exactStatement;
        }

        public LValue getlValue() {
            return lValue;
        }

        public int getDepth() {
            return depth;
        }

        public NamedVariable getName() {
            return name;
        }

        public ScopeKey getScopeKey() {
            return scopeKey;
        }

        public List<StatementContainer<StructuredStatement>> getNestedScope() {
            return nestedScope;
        }
    }

}