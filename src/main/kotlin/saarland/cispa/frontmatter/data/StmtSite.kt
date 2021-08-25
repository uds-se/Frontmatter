package saarland.cispa.frontmatter.data

import saarland.cispa.frontmatter.adapterMethodSubsignatures
import saarland.cispa.frontmatter.addViewSubsignatures
import saarland.cispa.frontmatter.findViewByIdSubsignatures
import saarland.cispa.frontmatter.getListViewSubsignature
import saarland.cispa.frontmatter.inflatorSubsignatures
import soot.NullType
import soot.SootMethod
import soot.jimple.AssignStmt
import soot.jimple.InvokeExpr
import soot.jimple.NewExpr
import soot.jimple.Stmt

data class StmtSite(val stmt: Stmt, val method: SootMethod) {
    fun isAddView(): Boolean {
        return stmt.containsInvokeExpr() && getInvokeExprSubsignature() in addViewSubsignatures
    }

    fun isGetView(): Boolean {
        return stmt.containsInvokeExpr() && getInvokeExprSubsignature() in adapterMethodSubsignatures
    }

    fun isInflate(): Boolean {
        if (!stmt.containsInvokeExpr()) return false
        return this.containsInvokeExpr() && getInvokeExprSubsignature() in inflatorSubsignatures
    }

    fun isGetListView(): Boolean {
        return this.containsInvokeExpr() && this.getInvokeExprSubsignature() in getListViewSubsignature
    }

    fun isNullType(): Boolean {
        return (stmt is AssignStmt) && (stmt.rightOp.type is NullType)
    }

    fun isNewExpr(): Boolean {
        return (stmt is AssignStmt) && (stmt.rightOp is NewExpr)
    }

    /**
     * r1 = findViewById()
     * r2 = new Button
     * */
    fun isFindViewById(): Boolean {
        val stmt = this.method.activeBody.units.getPredOf(this.stmt) as Stmt
        return (stmt.containsInvokeExpr() && stmt.invokeExpr.method.subSignature in findViewByIdSubsignatures) ||
            (this.containsInvokeExpr() && this.getInvokeExprSubsignature() in findViewByIdSubsignatures)
    }

    fun getNewStmtFromViewById(): StmtSite {
        require(this.isFindViewById())
        require(stmt is AssignStmt)
        return if (stmt.rightOp is NewExpr) this else {
            val nextStmt = method.activeBody.units.getSuccOf(stmt) as AssignStmt
            require(nextStmt.rightOp is NewExpr){"Wrong newExpr for a view @ $this"}
            StmtSite(nextStmt, method)
        }
    }

    fun getFindViewByIdStmt(): StmtSite {
        require(this.isFindViewById())
        require(stmt is AssignStmt)
        return if (stmt.rightOp !is NewExpr) this else {
            val prevStmt = method.activeBody.units.getPredOf(stmt) as AssignStmt
            require(prevStmt.invokeExpr.method.subSignature in findViewByIdSubsignatures)
            StmtSite(prevStmt, method)
        }
    }

    fun isGetChildAt(): Boolean {
        val stmt = this.method.activeBody.units.getPredOf(this.stmt) as Stmt
        return (stmt.containsInvokeExpr() && stmt.invokeExpr.method.name == "getChildAt") ||
            (this.containsInvokeExpr() && this.getInvokeExprSubsignature() == "android.view.View getChildAt(int)")
    }

    fun getNewStmtFromGetChildAt(): StmtSite {
        require(stmt is AssignStmt)
        return if (stmt.rightOp is NewExpr) this else {
            val nextStmt = method.activeBody.units.getSuccOf(stmt) as AssignStmt
            require(nextStmt.rightOp is NewExpr){"Wrong newExpr for a view @ $this"}
            StmtSite(nextStmt, method)
        }
    }

    fun containsInvokeExpr(): Boolean = stmt.containsInvokeExpr()
    fun getInvokeExpr(): InvokeExpr = stmt.invokeExpr
    fun getInvokeExprSubsignature(): String = stmt.invokeExpr.methodRef.subSignature.toString()
    fun isAssignStmt(): Boolean = stmt is AssignStmt
}
