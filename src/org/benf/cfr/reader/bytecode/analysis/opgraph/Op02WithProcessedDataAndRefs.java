package org.benf.cfr.reader.bytecode.analysis.opgraph;

import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.LValue;
import org.benf.cfr.reader.bytecode.analysis.parse.Statement;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.*;
import org.benf.cfr.reader.bytecode.analysis.parse.literal.TypedLiteral;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.ArrayVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.FieldVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.LocalVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.lvalue.StaticVariable;
import org.benf.cfr.reader.bytecode.analysis.parse.statement.*;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.BlockIdentifier;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.VariableNamer;
import org.benf.cfr.reader.bytecode.analysis.stack.StackDelta;
import org.benf.cfr.reader.bytecode.analysis.stack.StackEntry;
import org.benf.cfr.reader.bytecode.analysis.stack.StackEntryHolder;
import org.benf.cfr.reader.bytecode.analysis.stack.StackSim;
import org.benf.cfr.reader.bytecode.opcode.DecodedLookupSwitch;
import org.benf.cfr.reader.bytecode.opcode.DecodedTableSwitch;
import org.benf.cfr.reader.bytecode.opcode.JVMInstr;
import org.benf.cfr.reader.entities.ConstantPool;
import org.benf.cfr.reader.entities.ConstantPoolEntry;
import org.benf.cfr.reader.entities.ConstantPoolEntryMethodRef;
import org.benf.cfr.reader.entities.exceptions.ExceptionAggregator;
import org.benf.cfr.reader.entities.exceptions.ExceptionGroup;
import org.benf.cfr.reader.entities.exceptions.ExceptionTableEntry;
import org.benf.cfr.reader.util.ConfusedCFRException;
import org.benf.cfr.reader.util.ListFactory;
import org.benf.cfr.reader.util.bytestream.BaseByteData;
import org.benf.cfr.reader.util.functors.BinaryProcedure;
import org.benf.cfr.reader.util.graph.GraphVisitor;
import org.benf.cfr.reader.util.graph.GraphVisitorDFS;
import org.benf.cfr.reader.util.output.Dumpable;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.List;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: lee
 * Date: 09/03/2012
 * Time: 17:49
 * To change this template use File | Settings | File Templates.
 */
public class Op02WithProcessedDataAndRefs implements Dumpable, Graph<Op02WithProcessedDataAndRefs> {
    private InstrIndex index;

    private final JVMInstr instr;
    private final int originalRawOffset;
    private final byte[] rawData;

    private List<BlockIdentifier> containedInTheseBlocks = ListFactory.newList();
    private ExceptionGroup exceptionGroup = null;

    private final List<Op02WithProcessedDataAndRefs> targets = ListFactory.newList();
    private final List<Op02WithProcessedDataAndRefs> sources = ListFactory.newList();
    private final ConstantPool cp;
    private final ConstantPoolEntry[] cpEntries;
    private long stackDepthBeforeExecution = -1;
    private long stackDepthAfterExecution;
    private final List<StackEntryHolder> stackConsumed = ListFactory.newList();
    private final List<StackEntryHolder> stackProduced = ListFactory.newList();

    public Op02WithProcessedDataAndRefs(JVMInstr instr, byte[] rawData, int index, ConstantPool cp, ConstantPoolEntry[] cpEntries, int originalRawOffset) {
        this(instr, rawData, new InstrIndex(index), cp, cpEntries, originalRawOffset);
    }

    public Op02WithProcessedDataAndRefs(JVMInstr instr, byte[] rawData, InstrIndex index, ConstantPool cp, ConstantPoolEntry[] cpEntries, int originalRawOffset) {
        this.instr = instr;
        this.rawData = rawData;
        this.index = index;
        this.cp = cp;
        this.cpEntries = cpEntries;
        this.originalRawOffset = originalRawOffset;
    }

    public InstrIndex getIndex() {
        return index;
    }

    public void addTarget(Op02WithProcessedDataAndRefs node) {
        targets.add(node);
    }

    public void addSource(Op02WithProcessedDataAndRefs node) {
        sources.add(node);
    }

    public JVMInstr getInstr() {
        return instr;
    }

    public void replaceTarget(Op02WithProcessedDataAndRefs oldTarget, Op02WithProcessedDataAndRefs newTarget) {
        int index = targets.indexOf(oldTarget);
        if (index == -1) throw new ConfusedCFRException("Invalid target");
        targets.set(index, newTarget);
    }

    public void clearSources() {
        sources.clear();
    }
    /*
    public int getIndex() {
        return index;
    }

    public int getSubIndex() {
        return subindex;
    }
    */

    private int getInstrArgByte(int index) {
        return rawData[index];
    }

    private int getInstrArgShort(int index) {
        BaseByteData tmp = new BaseByteData(rawData);
        return tmp.getS2At(index);
    }

    @Override
    public List<Op02WithProcessedDataAndRefs> getTargets() {
        return targets;
    }

    @Override
    public List<Op02WithProcessedDataAndRefs> getSources() {
        return sources;
    }

    public void populateStackInfo(StackSim stackSim) {
        StackDelta stackDelta = instr.getStackDelta(rawData, cp, cpEntries, stackSim);
        if (stackDepthBeforeExecution != -1) {
            if (stackSim.getDepth() != stackDepthBeforeExecution) {
                throw new ConfusedCFRException("Invalid stack depths @ " + this + " : expected " + stackSim.getDepth() + " previously set to " + stackDepthBeforeExecution);
            }

            // Merge our current known arguments with these.
            // Since we merge into extant objects, we populate all the downstream
            // dependencies here, and don't need to recurse any futher.

            List<StackEntryHolder> alsoConsumed = ListFactory.newList();
            List<StackEntryHolder> alsoProduced = ListFactory.newList();
            StackSim unusedStack = stackSim.getChange(stackDelta, alsoConsumed, alsoProduced);
            if (alsoConsumed.size() != stackConsumed.size()) {
                throw new ConfusedCFRException("Unexpected stack sizes on merge");
            }
            for (int i = 0; i < stackConsumed.size(); ++i) {
                stackConsumed.get(i).mergeWith(alsoConsumed.get(i));
            }

        } else {
            this.stackDepthBeforeExecution = stackSim.getDepth();
            this.stackDepthAfterExecution = stackDepthBeforeExecution + stackDelta.getChange();

            StackSim newStackSim = stackSim.getChange(stackDelta, stackConsumed, stackProduced);

            for (Op02WithProcessedDataAndRefs target : targets) {
                target.populateStackInfo(newStackSim);
            }
        }
    }

    @Override
    public void dump(Dumper d) {
        d.print("" + index + " : " + instr + "\t Stack:" + stackDepthBeforeExecution + "\t");
        d.print("Consumes:[");
        for (StackEntryHolder stackEntryHolder : stackConsumed) {
            d.print(stackEntryHolder.toString() + " ");
        }
        d.print("] Produces:[");
        for (StackEntryHolder stackEntryHolder : stackProduced) {
            d.print(stackEntryHolder.toString() + " ");
        }
        d.print("] <- nodes");
        for (Op02WithProcessedDataAndRefs source : sources) {
            d.print(" " + source.index);
        }
        d.print(" -> nodes");
        for (Op02WithProcessedDataAndRefs target : targets) {
            d.print(" " + target.index);
        }
        d.print("\n");
    }


    public Statement createStatement(VariableNamer variableNamer) {
        switch (instr) {
            case ALOAD:
            case ILOAD:
            case LLOAD:
            case DLOAD:
            case FLOAD:
                return new Assignment(getStackLValue(0), new FieldExpression(new LocalVariable(getInstrArgByte(0), variableNamer, originalRawOffset)));
            case ALOAD_0:
            case ILOAD_0:
            case LLOAD_0:
            case DLOAD_0:
            case FLOAD_0:
                return new Assignment(getStackLValue(0), new FieldExpression(new LocalVariable(0, variableNamer, originalRawOffset)));
            case ALOAD_1:
            case ILOAD_1:
            case LLOAD_1:
            case DLOAD_1:
            case FLOAD_1:
                return new Assignment(getStackLValue(0), new FieldExpression(new LocalVariable(1, variableNamer, originalRawOffset)));
            case ALOAD_2:
            case ILOAD_2:
            case LLOAD_2:
            case DLOAD_2:
            case FLOAD_2:
                return new Assignment(getStackLValue(0), new FieldExpression(new LocalVariable(2, variableNamer, originalRawOffset)));
            case ALOAD_3:
            case ILOAD_3:
            case LLOAD_3:
            case DLOAD_3:
            case FLOAD_3:
                return new Assignment(getStackLValue(0), new FieldExpression(new LocalVariable(3, variableNamer, originalRawOffset)));
            case ACONST_NULL:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getNull()));
            case ICONST_M1:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getInt(-1)));
            case ICONST_0:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getInt(0)));
            case ICONST_1:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getInt(1)));
            case ICONST_2:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getInt(2)));
            case ICONST_3:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getInt(3)));
            case ICONST_4:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getInt(4)));
            case ICONST_5:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getInt(5)));
            case LCONST_0:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getLong(0)));
            case LCONST_1:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getLong(1)));
            case FCONST_0:
            case DCONST_0:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getDouble(0)));
            case FCONST_1:
            case DCONST_1:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getDouble(1)));
            case FCONST_2:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getDouble(2)));
            case BIPUSH:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getInt(rawData[0])));
            case SIPUSH:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getInt(getInstrArgShort(0))));
            case ISTORE:
            case ASTORE:
            case LSTORE:
            case DSTORE:
            case FSTORE:
                return new Assignment(new LocalVariable(getInstrArgByte(0), variableNamer, originalRawOffset), getStackRValue(0));
            case ISTORE_0:
            case ASTORE_0:
            case LSTORE_0:
            case DSTORE_0:
            case FSTORE_0:
                return new Assignment(new LocalVariable(0, variableNamer, originalRawOffset), getStackRValue(0));
            case ISTORE_1:
            case ASTORE_1:
            case LSTORE_1:
            case DSTORE_1:
            case FSTORE_1:
                return new Assignment(new LocalVariable(1, variableNamer, originalRawOffset), getStackRValue(0));
            case ISTORE_2:
            case ASTORE_2:
            case LSTORE_2:
            case DSTORE_2:
            case FSTORE_2:
                return new Assignment(new LocalVariable(2, variableNamer, originalRawOffset), getStackRValue(0));
            case ISTORE_3:
            case ASTORE_3:
            case LSTORE_3:
            case DSTORE_3:
            case FSTORE_3:
                return new Assignment(new LocalVariable(3, variableNamer, originalRawOffset), getStackRValue(0));
            case NEW:
                return new Assignment(getStackLValue(0), new NewObject(cp, cpEntries[0]));
            case NEWARRAY:
                return new Assignment(getStackLValue(0), new NewPrimitiveArray(getStackRValue(0), rawData[0]));
            case ANEWARRAY:
                return new Assignment(getStackLValue(0), new NewObjectArray(getStackRValue(0), cp, cpEntries[0]));
            case ARRAYLENGTH:
                return new Assignment(getStackLValue(0), new ArrayLength(getStackRValue(0)));
            case AALOAD:
            case IALOAD:
            case BALOAD:
            case CALOAD:
            case FALOAD:
            case LALOAD:
            case DALOAD:
                return new Assignment(getStackLValue(0), new ArrayIndex(getStackRValue(1), getStackRValue(0)));
            case AASTORE:
            case IASTORE:
            case BASTORE:
            case CASTORE:
            case FASTORE:
            case LASTORE:
            case DASTORE:
                return new Assignment(new ArrayVariable(new ArrayIndex(getStackRValue(2), getStackRValue(1))), getStackRValue(0));
            case LCMP:
            case DCMPG:
            case DCMPL:
            case FCMPG:
            case FCMPL:
            case LSUB:
            case LADD:
            case IADD:
            case FADD:
            case DADD:
            case ISUB:
            case DSUB:
            case FSUB:
            case IREM:
            case FREM:
            case LREM:
            case DREM:
            case IDIV:
            case FDIV:
            case DDIV:
            case IMUL:
            case DMUL:
            case FMUL:
            case LMUL:
            case IAND:
            case LAND:
            case LDIV:
            case LOR:
            case IOR:
            case LXOR:
            case IXOR:
            case ISHR:
            case ISHL:
            case LSHL:
            case LSHR:
            case IUSHR:
            case LUSHR: {
                Expression op = new ArithmeticOperation(getStackRValue(1), getStackRValue(0), ArithOp.getOpFor(instr));
                return new Assignment(getStackLValue(0), op);
            }
            case I2B:
            case I2C:
            case I2D:
            case I2F:
            case I2L:
            case I2S:
            case L2D:
            case L2F:
            case L2I:
            case F2D:
            case F2I:
            case F2L:
            case D2F:
            case D2I:
            case D2L:
                return new Assignment(getStackLValue(0), getStackRValue(0));
            case INSTANCEOF:
                return new Assignment(getStackLValue(0), new InstanceOfExpression(getStackRValue(0), cp, cpEntries[0]));
            case CHECKCAST:
                // Not strictly true, but matches our intermediate form.
                return new Assignment(getStackLValue(0), getStackRValue(0));
            case INVOKESTATIC: {
                StaticFunctionInvokation funcCall = new StaticFunctionInvokation(cp, cpEntries[0], getNStackRValuesAsExpressions(stackConsumed.size()));
                if (stackProduced.size() == 0) {
                    return new ExpressionStatement(funcCall);
                } else {
                    return new Assignment(getStackLValue(0), funcCall);
                }
            }
            case INVOKEVIRTUAL:
            case INVOKESPECIAL:
            case INVOKEINTERFACE: {
                ConstantPoolEntryMethodRef function = (ConstantPoolEntryMethodRef) cpEntries[0];
                StackValue object = getStackRValue(stackConsumed.size() - 1);
                MemberFunctionInvokation funcCall = new MemberFunctionInvokation(cp, function, object, getNStackRValuesAsExpressions(stackConsumed.size() - 1));
                if (function.isInitMethod(cp)) {
                    return new ConstructorStatement(funcCall);
                } else {
                    if (stackProduced.size() == 0) {
                        return new ExpressionStatement(funcCall);
                    } else {
                        return new Assignment(getStackLValue(0), funcCall);
                    }
                }
            }
            case RETURN:
                return new ReturnNothingStatement();
            case IF_ACMPEQ:
            case IF_ACMPNE:
            case IF_ICMPLT:
            case IF_ICMPGE:
            case IF_ICMPGT:
            case IF_ICMPNE:
            case IF_ICMPEQ:
            case IF_ICMPLE: {
                ConditionalExpression conditionalExpression = new ComparisonOperation(getStackRValue(1), getStackRValue(0), CompOp.getOpFor(instr));
                return new IfStatement(conditionalExpression);
            }
            case IFNONNULL: {
                ConditionalExpression conditionalExpression = new ComparisonOperation(getStackRValue(0), new Literal(TypedLiteral.getNull()), CompOp.NE);
                return new IfStatement(conditionalExpression);
            }
            case IFNULL: {
                ConditionalExpression conditionalExpression = new ComparisonOperation(getStackRValue(0), new Literal(TypedLiteral.getNull()), CompOp.EQ);
                return new IfStatement(conditionalExpression);
            }
            case IFEQ:
            case IFNE:
            case IFLE:
            case IFLT:
            case IFGT:
            case IFGE: {
                ConditionalExpression conditionalExpression = new ComparisonOperation(getStackRValue(0), new Literal(TypedLiteral.getInt(0)), CompOp.getOpFor(instr));
                return new IfStatement(conditionalExpression);
            }
            case GOTO: {
                return new GotoStatement();
            }
            case ATHROW:
                return new ThrowStatement(getStackRValue(0));
            case IRETURN:
            case ARETURN:
            case LRETURN:
            case DRETURN:
            case FRETURN:
                return new ReturnValueStatement(getStackRValue(0));
            case GETFIELD: {
                Expression fieldExpression = new FieldExpression(new FieldVariable(getStackRValue(0), cp, cpEntries[0]));
                return new Assignment(getStackLValue(0), fieldExpression);
            }
            case GETSTATIC:
                return new Assignment(getStackLValue(0), new FieldExpression(new StaticVariable(cp, cpEntries[0])));
            case PUTSTATIC:
                return new Assignment(new StaticVariable(cp, cpEntries[0]), getStackRValue(0));
            case PUTFIELD:
                return new Assignment(new FieldVariable(getStackRValue(1), cp, cpEntries[0]), getStackRValue(0));
            case DUP: {
                Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                Statement s2 = new Assignment(getStackLValue(1), getStackRValue(0));
                return new CompoundStatement(s1, s2);
            }
            case DUP_X1: {
                Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                Statement s2 = new Assignment(getStackLValue(1), getStackRValue(1));
                Statement s3 = new Assignment(getStackLValue(2), getStackRValue(0));
                return new CompoundStatement(s1, s2, s3);
            }
            case DUP_X2: {
                if (stackConsumed.get(1).getStackEntry().getType().getComputationCategory() == 2) {
                    Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                    Statement s2 = new Assignment(getStackLValue(1), getStackRValue(1));
                    Statement s3 = new Assignment(getStackLValue(2), getStackRValue(0));
                    return new CompoundStatement(s1, s2, s3);
                } else {
                    Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                    Statement s2 = new Assignment(getStackLValue(1), getStackRValue(1));
                    Statement s3 = new Assignment(getStackLValue(2), getStackRValue(2));
                    Statement s4 = new Assignment(getStackLValue(3), getStackRValue(0));
                    return new CompoundStatement(s1, s2, s3, s4);
                }
            }
            case DUP2: {
                if (stackConsumed.get(0).getStackEntry().getType().getComputationCategory() == 2) {
                    Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                    Statement s2 = new Assignment(getStackLValue(1), getStackRValue(0));
                    return new CompoundStatement(s1, s2);
                } else {
                    Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                    Statement s2 = new Assignment(getStackLValue(1), getStackRValue(1));
                    Statement s3 = new Assignment(getStackLValue(2), getStackRValue(0));
                    Statement s4 = new Assignment(getStackLValue(3), getStackRValue(1));
                    return new CompoundStatement(s1, s2, s3, s4);
                }
            }
            case DUP2_X1: {
                if (stackConsumed.get(0).getStackEntry().getType().getComputationCategory() == 2) {
                    Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                    Statement s2 = new Assignment(getStackLValue(1), getStackRValue(1));
                    Statement s3 = new Assignment(getStackLValue(2), getStackRValue(0));
                    return new CompoundStatement(s1, s2, s3);
                } else {
                    Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                    Statement s2 = new Assignment(getStackLValue(1), getStackRValue(1));
                    Statement s3 = new Assignment(getStackLValue(2), getStackRValue(2));
                    Statement s4 = new Assignment(getStackLValue(3), getStackRValue(0));
                    Statement s5 = new Assignment(getStackLValue(4), getStackRValue(1));
                    return new CompoundStatement(s1, s2, s3, s4, s5);
                }
            }
            case DUP2_X2: {
                if (stackConsumed.get(0).getStackEntry().getType().getComputationCategory() == 2) {
                    if (stackConsumed.get(1).getStackEntry().getType().getComputationCategory() == 2) {
                        // form 4
                        Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                        Statement s2 = new Assignment(getStackLValue(1), getStackRValue(1));
                        Statement s3 = new Assignment(getStackLValue(2), getStackRValue(0));
                        return new CompoundStatement(s1, s2, s3);
                    } else {
                        // form 2
                        Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                        Statement s2 = new Assignment(getStackLValue(1), getStackRValue(1));
                        Statement s3 = new Assignment(getStackLValue(2), getStackRValue(2));
                        Statement s4 = new Assignment(getStackLValue(3), getStackRValue(0));
                        return new CompoundStatement(s1, s2, s3, s4);
                    }
                } else {
                    if (stackConsumed.get(2).getStackEntry().getType().getComputationCategory() == 2) {
                        // form 3
                        Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                        Statement s2 = new Assignment(getStackLValue(1), getStackRValue(1));
                        Statement s3 = new Assignment(getStackLValue(2), getStackRValue(2));
                        Statement s4 = new Assignment(getStackLValue(3), getStackRValue(0));
                        Statement s5 = new Assignment(getStackLValue(4), getStackRValue(1));
                        return new CompoundStatement(s1, s2, s3, s4, s5);
                    } else {
                        // form 1
                        Statement s1 = new Assignment(getStackLValue(0), getStackRValue(0));
                        Statement s2 = new Assignment(getStackLValue(1), getStackRValue(1));
                        Statement s3 = new Assignment(getStackLValue(2), getStackRValue(2));
                        Statement s4 = new Assignment(getStackLValue(3), getStackRValue(3));
                        Statement s5 = new Assignment(getStackLValue(4), getStackRValue(0));
                        Statement s6 = new Assignment(getStackLValue(5), getStackRValue(1));
                        return new CompoundStatement(s1, s2, s3, s4, s5, s6);
                    }
                }
            }
            case LDC:
            case LDC_W:
            case LDC2_W:
                return new Assignment(getStackLValue(0), new Literal(TypedLiteral.getConstantPoolEntry(cp, cpEntries[0])));
            case MONITORENTER:
                return new CommentStatement("MONITORENTER {");
            case MONITOREXIT:
                return new CommentStatement("} MONITOREXIT");
            case FAKE_TRY:
                return new TryStatement(exceptionGroup);
            case FAKE_CATCH:
                return new CommentStatement("} catch ... {");
            case NOP:
                return new Nop();
            case POP:
                return new ExpressionStatement(getStackRValue(0));
            case POP2:
                if (stackConsumed.get(0).getStackEntry().getType().getComputationCategory() == 2) {
                    return new ExpressionStatement(getStackRValue(0));
                } else {
                    Statement s1 = new ExpressionStatement(getStackRValue(0));
                    Statement s2 = new ExpressionStatement(getStackRValue(1));
                    return new CompoundStatement(s1, s2);
                }
            case TABLESWITCH:
                return new SwitchStatement(getStackRValue(0), new DecodedTableSwitch(rawData, originalRawOffset));
            case LOOKUPSWITCH:
                return new SwitchStatement(getStackRValue(0), new DecodedLookupSwitch(rawData, originalRawOffset));
            case IINC: {
                int variableIndex = getInstrArgByte(0);
                int incrAmount = getInstrArgByte(1);
                // Can we have ++ / += instead?
                return new Assignment(new LocalVariable(variableIndex, variableNamer, originalRawOffset),
                        new ArithmeticOperation(new FieldExpression(new LocalVariable(variableIndex, variableNamer, originalRawOffset)), new Literal(TypedLiteral.getInt(incrAmount)), ArithOp.PLUS));
            }
            case DNEG:
            case FNEG:
            case LNEG:
            case INEG: {
                return new Assignment(getStackLValue(0),
                        new ArithmeticMonOperation(getStackRValue(0), ArithOp.MINUS));
            }

            case IINC_WIDE: {
                int variableIndex = getInstrArgShort(1);
                int incrAmount = getInstrArgShort(3);
                // Can we have ++ / += instead?
                return new Assignment(new LocalVariable(variableIndex, variableNamer, originalRawOffset),
                        new ArithmeticOperation(new FieldExpression(new LocalVariable(variableIndex, variableNamer, originalRawOffset)), new Literal(TypedLiteral.getInt(incrAmount)), ArithOp.PLUS));
            }
            default:
                throw new ConfusedCFRException("Not implemented - conversion to statement from " + instr);
        }
    }

    private StackValue getStackRValue(int idx) {
        StackEntryHolder stackEntryHolder = stackConsumed.get(idx);
        StackEntry stackEntry = stackEntryHolder.getStackEntry();
        stackEntry.incrementUsage();
        return new StackValue(stackEntry.getLValue());
    }

    private LValue getStackLValue(int idx) {
        StackEntryHolder stackEntryHolder = stackProduced.get(idx);
        StackEntry stackEntry = stackEntryHolder.getStackEntry();
        return stackEntry.getLValue();
    }

    private List<Expression> getNStackRValuesAsExpressions(int count) {
        List<Expression> res = ListFactory.newList();
        for (int i = 0; i < count; ++i) {
            res.add(getStackRValue(i));
        }
        return res;
    }

    @Override
    public String toString() {
        return "" + index + " : " + instr;
    }


    public static void populateStackInfo(List<Op02WithProcessedDataAndRefs> op2list) {
        // This dump block only exists because we're debugging bad stack size calcuations.
        Op02WithProcessedDataAndRefs o2start = op2list.get(0);
        try {
            o2start.populateStackInfo(new StackSim());
        } catch (ConfusedCFRException e) {
            Dumper dmp = new Dumper();
            dmp.print("----[known stack info]------------\n\n");
            for (Op02WithProcessedDataAndRefs op : op2list) {
                op.dump(dmp);
            }
            throw e;
        }

    }

    public static List<Op03SimpleStatement> convertToOp03List(List<Op02WithProcessedDataAndRefs> op2list, final VariableNamer variableNamer) {

        final List<Op03SimpleStatement> op03SimpleParseNodesTmp = ListFactory.newList();
        // Convert the op2s into a simple set of statements.
        // Do these need to be processed in a sensible order?  Could just iterate?
        final GraphConversionHelper<Op02WithProcessedDataAndRefs, Op03SimpleStatement> conversionHelper = new GraphConversionHelper<Op02WithProcessedDataAndRefs, Op03SimpleStatement>();
        // By only processing reachable bytecode, we ignore deliberate corruption.   However, we could
        // Nop out unreachable code, so as to not have this ugliness.
        // We start at 0 as that's not controversial ;)
        GraphVisitor o2Converter = new GraphVisitorDFS(op2list.get(0),
                new BinaryProcedure<Op02WithProcessedDataAndRefs, GraphVisitor<Op02WithProcessedDataAndRefs>>() {
                    @Override
                    public void call(Op02WithProcessedDataAndRefs arg1, GraphVisitor<Op02WithProcessedDataAndRefs> arg2) {
                        Op03SimpleStatement res = new Op03SimpleStatement(arg1, arg1.createStatement(variableNamer));
                        conversionHelper.registerOriginalAndNew(arg1, res);
                        op03SimpleParseNodesTmp.add(res);
                        for (Op02WithProcessedDataAndRefs target : arg1.getTargets()) {
                            arg2.enqueue(target);
                        }
                    }
                }
        );
        o2Converter.process();
        conversionHelper.patchUpRelations();
        return op03SimpleParseNodesTmp;
    }

    public static List<Op02WithProcessedDataAndRefs> insertExceptionBlocks(
            List<Op02WithProcessedDataAndRefs> op2list,
            ExceptionAggregator exceptions,
            Map<Integer, Integer> lutByOffset,
            ConstantPool cp
    ) {
        // Add entries from the exception table.  Since these are stored in terms of offsets, they're
        // only usable here until we mess around with the instruction structure, so do it early!
        for (ExceptionGroup exceptionGroup : exceptions.getExceptionsGroups()) {

            List<ExceptionTableEntry> rawes = exceptionGroup.getEntries();
            int originalIndex = lutByOffset.get((int) exceptionGroup.getBytecodeIndexFrom());
            Op02WithProcessedDataAndRefs startInstruction = op2list.get(originalIndex);

            List<Op02WithProcessedDataAndRefs> handlerTargets = ListFactory.newList();
            for (ExceptionTableEntry exceptionTableEntry : rawes) {
                short handler = exceptionTableEntry.getBytecode_index_handler();
                int handlerIndex = lutByOffset.get((int) handler);
                Op02WithProcessedDataAndRefs handerTarget = op2list.get(handlerIndex);
                handlerTargets.add(handerTarget);
            }

            // Unlink startInstruction from its source, add a new instruction in there, which has a
            // default target of startInstruction, but additional targets of handlerTargets.
            Op02WithProcessedDataAndRefs tryOp =
                    new Op02WithProcessedDataAndRefs(JVMInstr.FAKE_TRY, null, startInstruction.getIndex().justBefore(), cp, null, -1);
            BlockIdentifier tryBlockIdentifier = exceptionGroup.getTryBlockIdentifier();
            tryOp.containedInTheseBlocks.addAll(startInstruction.containedInTheseBlocks);
            tryOp.exceptionGroup = exceptionGroup;


//            tryOp.firstStatementInThisBlock = tryBlockIdentifier;
//            tryOp.containedInTheseBlocks.add(tryBlockIdentifier);

            int inclusiveLastIndex = lutByOffset.get((int) exceptionGroup.getByteCodeIndexTo());
            for (int x = originalIndex; x <= inclusiveLastIndex; ++x) {
                op2list.get(x).containedInTheseBlocks.add(tryBlockIdentifier);
            }


            // All operations which pointed to start should now point to our TRY
            if (startInstruction.getSources().isEmpty())
                throw new ConfusedCFRException("Can't install exception handler infront of nothing");
            for (Op02WithProcessedDataAndRefs source : startInstruction.getSources()) {
                source.replaceTarget(startInstruction, tryOp);
                tryOp.addSource(source);
            }

            // Add tryBlockIdentifier to each block in the original.

            // Given that we're protecting a certain block,
            // these are the different catch blocks, one for each caught type.

            for (Op02WithProcessedDataAndRefs tryTarget : handlerTargets) {
                /*
                * tryTarget should not have a previous FAKE_CATCH source.
                */
                List<Op02WithProcessedDataAndRefs> tryTargetSources = tryTarget.getSources();
                if (!tryTargetSources.isEmpty()) {
                    if (tryTargetSources.size() > 1) {
                        throw new ConfusedCFRException("Try target has >1 source");
                    }
                    Op02WithProcessedDataAndRefs source = tryTargetSources.get(0);
                    if (source.getInstr() != JVMInstr.FAKE_CATCH) {
                        throw new ConfusedCFRException("non catch before exception catch block");
                    }
                    // We won't add another catch.
                    // TODO : validate the type of the catch.
                    continue;
                }


                Op02WithProcessedDataAndRefs preCatchOp =
                        new Op02WithProcessedDataAndRefs(JVMInstr.FAKE_CATCH, null, tryTarget.getIndex().justBefore(), cp, null, -1);
                preCatchOp.containedInTheseBlocks.addAll(tryTarget.getContainedInTheseBlocks());
                preCatchOp.exceptionGroup = exceptionGroup;

                op2list.add(preCatchOp);

                tryOp.addTarget(preCatchOp);
                preCatchOp.addSource(tryOp);
                preCatchOp.addTarget(tryTarget);
                tryTarget.addSource(preCatchOp);
            }
            tryOp.addTarget(startInstruction);
            startInstruction.clearSources();
            startInstruction.addSource(tryOp);
            op2list.add(tryOp);
        }
        return op2list;
    }

    public List<BlockIdentifier> getContainedInTheseBlocks() {
        return containedInTheseBlocks;
    }

}
