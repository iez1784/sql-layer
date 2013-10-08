/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.sql.pg;

import com.foundationdb.ais.model.UserTable;
import com.foundationdb.server.AkType;
import com.foundationdb.server.types3.TInstance;
import com.foundationdb.sql.optimizer.TypesTranslation;
import com.foundationdb.sql.optimizer.plan.BasePlannable;
import com.foundationdb.sql.optimizer.plan.PhysicalSelect;
import com.foundationdb.sql.optimizer.plan.PhysicalUpdate;
import com.foundationdb.sql.optimizer.rule.PlanContext;
import com.foundationdb.sql.parser.DMLStatementNode;
import com.foundationdb.sql.parser.ParameterNode;
import com.foundationdb.sql.parser.StatementNode;
import com.foundationdb.sql.server.ServerPlanContext;
import com.foundationdb.sql.types.DataTypeDescriptor;

import java.util.List;
import java.util.Set;

public abstract class PostgresBaseOperatorStatement extends PostgresDMLStatement
{
    private PostgresOperatorCompiler compiler;
    private Set<UserTable> affectedTables;

    protected PostgresBaseOperatorStatement(PostgresOperatorCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public PostgresStatement finishGenerating(PostgresServerSession server,
                                              String sql, StatementNode stmt,
                                              List<ParameterNode> params, int[] paramTypes) {
        DMLStatementNode dmlStmt = (DMLStatementNode)stmt;
        PlanContext planContext = new ServerPlanContext(compiler, new PostgresQueryContext(server));
        BasePlannable result = compiler.compile(dmlStmt, params, planContext);
        PostgresType[] parameterTypes = getParameterTypes(result.getParameterTypes(),
                                                          paramTypes);

        final PostgresBaseOperatorStatement pbos;
        if (result.isUpdate())
            pbos = compiler.generateUpdate(this,
                                           (PhysicalUpdate)result, stmt.statementToString(),
                                           parameterTypes);
        else
            pbos = compiler.generateSelect(this,
                                           (PhysicalSelect)result,
                                           parameterTypes);
        pbos.compiler = null;
        pbos.setAffectedTables(result.getAffectedTables());
        return pbos;
    }

    protected PostgresType[] getParameterTypes(DataTypeDescriptor[] sqlTypes,
                                               int[] paramTypes) {
        if (sqlTypes == null) 
            return null;
        int nparams = sqlTypes.length;
        PostgresType[] parameterTypes = new PostgresType[nparams];
        for (int i = 0; i < nparams; i++) {
            DataTypeDescriptor sqlType = sqlTypes[i];
            PostgresType pgType = null;
            if (sqlType != null) {
                AkType akType = TypesTranslation.sqlTypeToAkType(sqlType);
                TInstance tInstance = TypesTranslation.toTInstance(sqlType);
                pgType = PostgresType.fromDerby(sqlType, akType, tInstance);
            }
            if ((paramTypes != null) && (i < paramTypes.length)) {
                // Make a type that has the target that the query wants, with the
                // OID that the client proposed to send so that we
                // decode it properly.
                PostgresType.TypeOid oid = PostgresType.TypeOid.fromOid(paramTypes[i]);
                if (oid != null) {
                    if (pgType == null)
                        pgType = new PostgresType(oid, (short)-1, -1, null, null);
                    else
                        pgType = new PostgresType(oid,  (short)-1, -1,
                                                  pgType.getAkType(),
                                                  pgType.getInstance());
                }
            }
            parameterTypes[i] = pgType;
        }
        return parameterTypes;
    }

    public Set<UserTable> getAffectedTables() {
        return affectedTables;
    }

    public void setAffectedTables(Set<UserTable> affectedTables) {
        this.affectedTables = affectedTables;
    }
}
