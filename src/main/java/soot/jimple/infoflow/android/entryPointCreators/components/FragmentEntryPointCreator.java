package soot.jimple.infoflow.android.entryPointCreators.components;

import heros.TwoElementSet;
import soot.Local;
import soot.RefType;
import soot.SootClass;
import soot.Type;
import soot.jimple.Jimple;
import soot.jimple.NopStmt;
import soot.jimple.NullConstant;
import soot.jimple.Stmt;
import soot.jimple.infoflow.android.entryPointCreators.AndroidEntryPointConstants;
import soot.jimple.infoflow.android.manifest.ProcessManifest;

import java.util.Collections;
import java.util.List;

/**
 * Entry point creator for Android fragments, based on Flowdroid class
 * Added support for new methods and android.support.v4.Fragments class
 */
public class FragmentEntryPointCreator extends AbstractComponentEntryPointCreator {

    public FragmentEntryPointCreator(SootClass component, SootClass applicationClass, ProcessManifest manifest) {
        super(component, applicationClass, manifest);
    }

    @Override
    protected void generateComponentLifecycle() {
        // We need the local for the parent activity
        Local lcActivity = body.getParameterLocal(getDefaultMainMethodParams().size());
        if (!(lcActivity.getType() instanceof RefType))
            throw new RuntimeException("Activities must be reference types");
        RefType rtActivity = (RefType) lcActivity.getType();
        SootClass scActivity = rtActivity.getSootClass();

        // The onAttachFragment() callbacks tells the activity that a
        // new fragment was attached
        // TODO: because of late binding we may not have an activity here, but just dummyActivity
        TwoElementSet<SootClass> classAndFragment = new TwoElementSet<>(component, scActivity);
        Stmt afterOnAttachFragment = Jimple.v().newNopStmt();
        createIfStmt(afterOnAttachFragment);
        if (component.declaresMethod(AndroidEntryPointConstants.ACTIVITY_ONATTACHFRAGMENT)) {
            searchAndBuildMethod(AndroidEntryPointConstants.ACTIVITY_ONATTACHFRAGMENT, component, thisLocal, classAndFragment);
        }
        else if (component.declaresMethod(AndroidSupportEntryPointConstants.FRAGMENTACTIVITY_ONATTACHFRAGMENT)) {
            searchAndBuildMethod(AndroidSupportEntryPointConstants.FRAGMENTACTIVITY_ONATTACHFRAGMENT, component, thisLocal, classAndFragment);
        }
        body.getUnits().add(afterOnAttachFragment);

        // Render the fragment lifecycle
        generateFragmentLifecycle(component, thisLocal, scActivity);
    }

    /**
     * Generates the lifecycle for an Android Fragment class
     *
     * @param currentClass The class for which to build the fragment lifecycle
     * @param classLocal   The local referencing an instance of the current class
     */
    private void generateFragmentLifecycle(SootClass currentClass, Local classLocal, SootClass activity) {
        NopStmt endFragmentStmt = Jimple.v().newNopStmt();
        createIfStmt(endFragmentStmt);

        // 1. onAttach: // we have 2 onAttach methods, one is deprecated
        Stmt onAttachActivityStmt = searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONATTACH, currentClass, classLocal, Collections.singleton(activity));
        if (onAttachActivityStmt == null)
            body.getUnits().add(onAttachActivityStmt = Jimple.v().newNopStmt());

        // 1. onAttach: // we have 2 onAttach methods, one is deprecated
        if (currentClass.declaresMethod(AndroidSupportEntryPointConstants.NEW_FRAGMENT_ONATTACH)) {
            Stmt onAttachContextStmt = searchAndBuildMethod(AndroidSupportEntryPointConstants.NEW_FRAGMENT_ONATTACH, currentClass, classLocal, Collections.singleton(activity));
            if (onAttachContextStmt == null)
                body.getUnits().add(onAttachContextStmt = Jimple.v().newNopStmt());
        }
        // 2. onCreate:
        Stmt onCreateStmt = searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONCREATE, currentClass, classLocal);
        if (onCreateStmt == null)
            body.getUnits().add(onCreateStmt = Jimple.v().newNopStmt());

        // 3. onCreateView:
        Stmt onCreateViewStmt = searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONCREATEVIEW, currentClass, classLocal);
        if (onCreateViewStmt == null)
            body.getUnits().add(onCreateViewStmt = Jimple.v().newNopStmt());

        Stmt onViewCreatedStmt = searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONVIEWCREATED, currentClass, classLocal);
        if (onViewCreatedStmt == null)
            body.getUnits().add(onViewCreatedStmt = Jimple.v().newNopStmt());

        // 0. onActivityCreated:
        Stmt onActCreatedStmt = searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONACTIVITYCREATED, currentClass, classLocal);
        if (onActCreatedStmt == null)
            body.getUnits().add(onActCreatedStmt = Jimple.v().newNopStmt());

        // 4. onStart:
        Stmt onStartStmt = searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONSTART, currentClass, classLocal);
        if (onStartStmt == null)
            body.getUnits().add(onStartStmt = Jimple.v().newNopStmt());

        // 5. onResume:
        Stmt onResumeStmt = Jimple.v().newNopStmt();
        body.getUnits().add(onResumeStmt);
        searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONRESUME, currentClass, classLocal);

        // Add the fragment callbacks
        addCallbackMethods();

        // 6. onPause:
        searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONPAUSE, currentClass, classLocal);
        createIfStmt(onResumeStmt);

        // 7. onSaveInstanceState:
        searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONSAVEINSTANCESTATE, currentClass, classLocal);

        // 8. onStop:
        searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONSTOP, currentClass, classLocal);
        createIfStmt(onCreateViewStmt);
        createIfStmt(onStartStmt);

        // 9. onDestroyView:
        searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONDESTROYVIEW, currentClass, classLocal);
        createIfStmt(onCreateViewStmt);

        // 10. onDestroy:
        searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONDESTROY, currentClass, classLocal);

        // 11. onDetach:
        searchAndBuildMethod(AndroidEntryPointConstants.FRAGMENT_ONDETACH, currentClass, classLocal);
        createIfStmt(onAttachActivityStmt);

        body.getUnits().add(Jimple.v().newAssignStmt(classLocal, NullConstant.v()));
        body.getUnits().add(endFragmentStmt);
    }

    @Override
    protected List<Type> getAdditionalMainMethodParams() {
        return Collections.singletonList(RefType.v(AndroidEntryPointConstants.ACTIVITYCLASS));
    }

}
