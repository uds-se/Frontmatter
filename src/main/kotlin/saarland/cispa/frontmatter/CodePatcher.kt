package saarland.cispa.frontmatter

import soot.PackManager
import soot.jimple.infoflow.cfg.LibraryClassPatcher

object CodePatcher {
    fun patchLibraryClass() {
        /** Patch the callgraph to support additional edges. We do this now, because during callback discovery, the context-insensitive callgraph
        algorithm would flood us with invalid edges.
        Patch the android.os.Handler implementation
        Patch the java.lang.Thread implementation
        Patch the android.app.Activity implementation (getApplication())
        Patch the java.util.Timer implementation
        Patch activity getFragmentManager() XXX: is uses android.app.FragmentManager which is deprecated in Android 28
        Patch the various overloads of Message.obtain()
         */
        val patcher = LibraryClassPatcher()
        patcher.patchLibraries()
        PackManager.v().getPack("wjpp").apply()
    }
}
