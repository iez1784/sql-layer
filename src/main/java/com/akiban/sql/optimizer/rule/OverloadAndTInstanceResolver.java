/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.optimizer.rule;

import com.akiban.server.t3expressions.OverladResolutionResult;
import com.akiban.server.t3expressions.OverloadResolver;
import com.akiban.server.t3expressions.T3ScalarsRegistery;
import com.akiban.server.t3expressions.TClassPossibility;
import com.akiban.server.types3.LazyListBase;
import com.akiban.server.types3.TClass;
import com.akiban.server.types3.TExecutionContext;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.TOverloadResult;
import com.akiban.server.types3.TPreptimeContext;
import com.akiban.server.types3.TPreptimeValue;
import com.akiban.server.types3.aksql.AkBundle;
import com.akiban.server.types3.aksql.AkBundle.AkSwitcher;
import com.akiban.server.types3.aksql.aktypes.AkBool;
import com.akiban.server.types3.aksql.aktypes.AkNumeric;
import com.akiban.server.types3.aksql.aktypes.AkString;
import com.akiban.server.types3.common.types.StringAttribute;
import com.akiban.server.types3.common.types.StringFactory;
import com.akiban.server.types3.common.types.StringFactory.Charset;
import com.akiban.server.types3.mcompat.mtypes.MDouble;
import com.akiban.server.types3.mcompat.mtypes.MNumeric;
import com.akiban.server.types3.mcompat.mtypes.MString;
import com.akiban.server.types3.pvalue.PValue;
import com.akiban.server.types3.pvalue.PValueSource;
import com.akiban.server.types3.texpressions.TValidatedOverload;
import com.akiban.sql.optimizer.plan.AggregateFunctionExpression;
import com.akiban.sql.optimizer.plan.AnyCondition;
import com.akiban.sql.optimizer.plan.BooleanConstantExpression;
import com.akiban.sql.optimizer.plan.BooleanOperationExpression;
import com.akiban.sql.optimizer.plan.CastExpression;
import com.akiban.sql.optimizer.plan.ColumnExpression;
import com.akiban.sql.optimizer.plan.ComparisonCondition;
import com.akiban.sql.optimizer.plan.ConstantExpression;
import com.akiban.sql.optimizer.plan.ExistsCondition;
import com.akiban.sql.optimizer.plan.ExpressionNode;
import com.akiban.sql.optimizer.plan.ExpressionRewriteVisitor;
import com.akiban.sql.optimizer.plan.ExpressionVisitor;
import com.akiban.sql.optimizer.plan.FunctionExpression;
import com.akiban.sql.optimizer.plan.IfElseExpression;
import com.akiban.sql.optimizer.plan.InListCondition;
import com.akiban.sql.optimizer.plan.ParameterCondition;
import com.akiban.sql.optimizer.plan.ParameterExpression;
import com.akiban.sql.optimizer.plan.PlanContext;
import com.akiban.sql.optimizer.plan.PlanNode;
import com.akiban.sql.optimizer.plan.PlanVisitor;
import com.akiban.sql.optimizer.plan.SubqueryResultSetExpression;
import com.akiban.sql.optimizer.plan.SubqueryValueExpression;
import com.akiban.sql.optimizer.rule.ConstantFolder.Folder;
import com.akiban.sql.types.DataTypeDescriptor;
import com.akiban.sql.types.TypeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class OverloadAndTInstanceResolver extends BaseRule {
    private static final Logger logger = LoggerFactory.getLogger(OverloadAndTInstanceResolver.class);

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    public void apply(PlanContext plan) {
        new ResolvingVistor(plan).resolve(plan.getPlan());
    }

    static class ResolvingVistor implements PlanVisitor, ExpressionRewriteVisitor {

        private Folder folder;
        private OverloadResolver resolver;
        private T3ScalarsRegistery registry;


        ResolvingVistor(PlanContext context) {
            folder = new Folder(context);
        }

        public void resolve(PlanNode root) {
            root.accept(this);
        }

        @Override
        public boolean visitChildrenFirst(ExpressionNode n) {
            return true;
        }

        @Override
        public boolean visitEnter(PlanNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(PlanNode n) {
            return true;
        }

        @Override
        public boolean visit(PlanNode n) {
            return true;
        }

        @Override
        public ExpressionNode visit(ExpressionNode n) {
            if (n instanceof CastExpression)
                n = handleCastExpression((CastExpression) n);
            else if (n instanceof FunctionExpression)
                n = handleFunctionExpression((FunctionExpression) n);
            else if (n instanceof IfElseExpression)
                n = handleIfElseExpression((IfElseExpression) n);
            else if (n instanceof AggregateFunctionExpression)
                n = handleAggregateFunctionExpression((AggregateFunctionExpression) n);
            else if (n instanceof ExistsCondition)
                n = handleExistsCondition((ExistsCondition) n);
            else if (n instanceof SubqueryValueExpression)
                n = handleSubqueryValueExpression((SubqueryValueExpression) n);
            else if (n instanceof SubqueryResultSetExpression)
                n = handleSubqueryResultSetExpression((SubqueryResultSetExpression) n);
            else if (n instanceof AnyCondition)
                n = handleAnyCondition((AnyCondition) n);
            else if (n instanceof ComparisonCondition)
                n = handleComparisonCondition((ComparisonCondition) n);
            else if (n instanceof ColumnExpression)
                n = handleColumnExpression((ColumnExpression) n);
            else if (n instanceof InListCondition)
                n = handleInListCondition((InListCondition) n);
            else if (n instanceof ParameterCondition)
                n = handleParameterCondition((ParameterCondition) n);
            else if (n instanceof ParameterExpression)
                n = handleParameterExpression((ParameterExpression) n);
            else if (n instanceof BooleanOperationExpression)
                n = handleBooleanOperationExpression((BooleanOperationExpression) n);
            else if (n instanceof BooleanConstantExpression)
                n = handleBooleanConstantExpression((BooleanConstantExpression) n);
            else if (n instanceof ConstantExpression)
                n = handleConstantExpression((ConstantExpression) n);
            else
                logger.warn("unrecognized ExpressionNode subclass: {}", n.getClass());
            n = folder.visit(n);
            
            return n;
        }

        ExpressionNode handleCastExpression(CastExpression expression) {
            return expression;
        }

        ExpressionNode handleFunctionExpression(FunctionExpression expression) {
            List<ExpressionNode> operands = expression.getOperands();
            List<TClass> operandClasses = new ArrayList<TClass>(operands.size());
            for (ExpressionNode operand : operands)
                operandClasses.add(tclass(operand));

            OverladResolutionResult resolutionResult = registry.get(expression.getFunction(), operandClasses);

            // cast operands
            for (int i = 0, operandsSize = operands.size(); i < operandsSize; i++) {
                ExpressionNode operand = castTo(operands.get(i), resolutionResult.tClass(i));
                operands.set(i, operand);
            }

            TValidatedOverload overload = resolutionResult.overload();

            final List<TPreptimeValue> operandValues = new ArrayList<TPreptimeValue>(operands.size());
            List<TInstance> operandInstances = new ArrayList<TInstance>(operands.size());
            for (ExpressionNode operand : operands) {
                TPreptimeValue preptimeValue = operand.getPreptimeValue();
                operandValues.add(preptimeValue);
                operandInstances.add(preptimeValue.instance());
            }

            TOverloadResult overloadResultStrategy = overload.resultStrategy();
            TInstance resultInstance;

            TPreptimeContext context = new TPreptimeContext(operandInstances);
            switch (overloadResultStrategy.category()) {
            case CUSTOM:
                resultInstance = overloadResultStrategy.customRule().resultInstance(operandValues, context);
                break;
            case FIXED:
                resultInstance = overloadResultStrategy.fixed();
                break;
            case PICKING:
                resultInstance = resolutionResult.pickingType();
                break;
            default:
                throw new AssertionError(overloadResultStrategy.category());
            }
            context.setOutputType(resultInstance);
            overload.finishPreptimePhase(context);

            // TODO should this be in Folder?
            // -----------------------------------------------------------
            TPreptimeValue result = overload.evaluateConstant(context, new LazyListBase<TPreptimeValue>() {
                @Override
                public TPreptimeValue get(int i) {
                    return operandValues.get(i);
                }

                @Override
                public int size() {
                    return operandValues.size();
                }
            });
            if (result.value() != null) {
                assert false : "create constant expression" ; // TODO
            }
            // -----------------------------------------------------------

            TPreptimeValue preptimeValue = new TPreptimeValue(resultInstance);
            expression.setPreptimeValue(preptimeValue);
            return expression;
        }

        ExpressionNode handleIfElseExpression(IfElseExpression expression) {
            ExpressionNode thenExpr = expression.getThenExpression();
            ExpressionNode elseExpr = expression.getElseExpression();

            // constant-fold if the condition is constant
            PValueSource conditionVal = pval(expression.getTestCondition());
            if (conditionVal != null) {
                boolean conditionMet = conditionVal.getBoolean(false);
                return conditionMet ? thenExpr : elseExpr;
            }

            TInstance thenType = tinst(thenExpr);
            TInstance elseType = tinst(elseExpr);

            TClassPossibility commonPossibility = registry.commonTClass(thenType.typeClass(), elseType.typeClass());
            if (commonPossibility.isAny() || commonPossibility.isNone())
                throw error("couldn't determine a type for CASE expression");
            TClass commonClass = commonPossibility.get();
            thenExpr = castTo(thenExpr, commonClass);
            elseExpr = castTo(elseExpr, commonClass);
            TInstance resultInstance = commonClass.pickInstance(tinst(thenExpr), tinst(elseExpr));
            expression.setPreptimeValue(new TPreptimeValue(resultInstance));
            return expression;
        }

        ExpressionNode handleAggregateFunctionExpression(AggregateFunctionExpression expression) {
            throw new UnsupportedOperationException(); // TODO
        }

        ExpressionNode handleExistsCondition(ExistsCondition expression) {
            return boolExpr(expression);
        }

        ExpressionNode handleSubqueryValueExpression(SubqueryValueExpression expression) {
            throw new UnsupportedOperationException(); // TODO
        }

        ExpressionNode handleSubqueryResultSetExpression(SubqueryResultSetExpression expression) {
            return boolExpr(expression);
        }

        ExpressionNode handleAnyCondition(AnyCondition expression) {
            return boolExpr(expression);
        }

        ExpressionNode handleComparisonCondition(ComparisonCondition expression) {
            return boolExpr(expression);
        }

        ExpressionNode handleColumnExpression(ColumnExpression expression) {
            expression.setPreptimeValue(new TPreptimeValue(expression.getColumn().tInstance()));
            return expression;
        }

        ExpressionNode handleInListCondition(InListCondition expression) {
            return boolExpr(expression);
        }

        ExpressionNode handleParameterCondition(ParameterCondition expression) {
            return boolExpr(expression);
        }

        ExpressionNode handleParameterExpression(ParameterExpression expression) {
            return expression;
        }

        ExpressionNode handleBooleanOperationExpression(BooleanOperationExpression expression) {
            return boolExpr(expression);
        }

        ExpressionNode handleBooleanConstantExpression(BooleanConstantExpression expression) {
            return boolExpr(expression);
        }

        ExpressionNode handleConstantExpression(ConstantExpression expression) {
            // TODO temporary bridge between AkType world and types3 world
            TInstance instance = tinst(expression.getSQLtype());
            Object ovalue = expression.getValue();
            PValue pvalue = new PValue(instance.typeClass().underlyingType());
            if (ovalue == null) {
                pvalue.putNull();
            }
            else {
                switch (expression.getAkType().underlyingType()) {
                case BOOLEAN_AKTYPE:
                    pvalue.putBool((Boolean)ovalue);
                    break;
                case LONG_AKTYPE:
                    pvalue.putInt64((Long)ovalue);
                    break;
                case FLOAT_AKTYPE:
                    pvalue.putFloat((Float)ovalue);
                    break;
                case DOUBLE_AKTYPE:
                    pvalue.putDouble((Double)ovalue);
                    break;
                case OBJECT_AKTYPE:
                    if (ovalue instanceof String) {
                        assert AkString.VARCHAR.equals(instance.typeClass()) : instance;
                        pvalue.putObject(ovalue);
                        assert false : "cacher needed";
                        pvalue.setCacher(null); // TODO
                    }
                    break;
                }
            }
            return expression;
        }

        private static ExpressionNode castTo(ExpressionNode expression, TClass targetClass) {
            if (targetClass.equals(tclass(expression)))
                return expression;
            TInstance instance = targetClass.instance();
            return new CastExpression(expression, instance.dataTypeDescriptor(), expression.getSQLsource());
        }

        private static TClass tclass(ExpressionNode operand) {
            TInstance tinst = tinst(operand);
            return tinst == null ? null : tinst.typeClass();
        }

        private static TInstance tinst(ExpressionNode node) {
            return node.getPreptimeValue().instance();
        }

        private static PValueSource pval(ExpressionNode expression) {
            return expression.getPreptimeValue().value();
        }

        private static RuntimeException error(String message) {
            throw new RuntimeException(message); // TODO what actual error type?
        }

        private static TInstance tinst(DataTypeDescriptor descriptor) {
            
            switch (descriptor.getTypeId().getTypeFormatId()) {
            case TypeId.FormatIds.BOOLEAN_TYPE_ID:
                return AkBool.INSTANCE.instance();
            case TypeId.FormatIds.VARCHAR_TYPE_ID:
            case TypeId.FormatIds.CHAR_TYPE_ID:
                Charset charset = StringFactory.Charset.valueOf(descriptor.getCharacterAttributes().getCharacterSet());
                return MString.VARCHAR.instance(descriptor.getMaximumWidth(), charset.ordinal());
            case TypeId.FormatIds.DECIMAL_TYPE_ID:
            case TypeId.FormatIds.NUMERIC_TYPE_ID:
                // TODO should be aksql type
                return MNumeric.DECIMAL.instance(descriptor.getPrecision(), descriptor.getScale());
            case TypeId.FormatIds.DOUBLE_TYPE_ID:
                return AkNumeric.DOUBLE.instance();
            case TypeId.FormatIds.INT_TYPE_ID:
                return AkNumeric.INT.instance();
            case TypeId.FormatIds.TINYINT_TYPE_ID:
            case TypeId.FormatIds.SMALLINT_TYPE_ID:
                return AkNumeric.SMALLINT.instance();
            case TypeId.FormatIds.LONGINT_TYPE_ID:
                return AkNumeric.BIGINT.instance();

            case TypeId.FormatIds.BIT_TYPE_ID:
            case TypeId.FormatIds.DATE_TYPE_ID:
            case TypeId.FormatIds.LONGVARBIT_TYPE_ID:
            case TypeId.FormatIds.LONGVARCHAR_TYPE_ID:
            case TypeId.FormatIds.REAL_TYPE_ID:
            case TypeId.FormatIds.REF_TYPE_ID:
            case TypeId.FormatIds.TIME_TYPE_ID:
            case TypeId.FormatIds.TIMESTAMP_TYPE_ID:
            case TypeId.FormatIds.USERDEFINED_TYPE_ID:
            case TypeId.FormatIds.VARBIT_TYPE_ID:
            case TypeId.FormatIds.BLOB_TYPE_ID:
            case TypeId.FormatIds.CLOB_TYPE_ID:
            case TypeId.FormatIds.XML_TYPE_ID:
            case TypeId.FormatIds.ROW_MULTISET_TYPE_ID_IMPL:
            case TypeId.FormatIds.INTERVAL_YEAR_MONTH_ID:
            case TypeId.FormatIds.INTERVAL_DAY_SECOND_ID:
                throw new UnsupportedOperationException("unsupported type: " + descriptor);
            default:
                throw new AssertionError("unknown type: " + descriptor);
            }
        }
    }

    private static ExpressionNode boolExpr(ExpressionNode expression) {
        expression.setPreptimeValue(new TPreptimeValue(AkBool.INSTANCE.instance()));
        return expression;
    }
}
