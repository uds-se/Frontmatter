package saarland.cispa.frontmatter

import saarland.cispa.frontmatter.cg.CallgraphAssembler
import soot.RefType
import soot.jimple.infoflow.android.entryPointCreators.AndroidEntryPointConstants
import java.io.IOException
import java.io.InputStream
import java.util.*


const val textSeparator = 0x1E.toChar().toString()
const val actionBarCompatLayoutPrefix = "abc_"  // https://android.googlesource.com/platform/frameworks/support/+/ee7c9fb
val textAttributes = setOf("text", "contentDescription", "textOn", "textOff", "title", "label", "hint", "accessibilityPaneTitle", "tooltipText") //"src" "style"
const val stringResPrefix = "@string"
const val onCreateViewSubsignature = "android.view.View onCreateView(android.view.LayoutInflater,android.view.ViewGroup,android.os.Bundle)"
const val onViewCreatedSubsignature = "void onViewCreated(android.view.View,android.os.Bundle)"
const val dummyMainMethod = "dummyMainMethod"
const val dummyMainClass = "dummyMainClass"

const val BROADCAST_ONRECEIVE = "void onReceive(android.content.Context,android.content.Intent)"
const val AppWidgetProviderSubsignatures = "void onUpdate(android.content.Context,android.appwidget.AppWidgetManager,int[])"

val broadcastLifecycleSubsignatures = setOf(BROADCAST_ONRECEIVE, AppWidgetProviderSubsignatures)
val registerBroadcastSubsignatures = setOf(
    "android.content.Intent registerReceiver(android.content.BroadcastReceiver,android.content.IntentFilter)",
    "android.content.Intent registerReceiver(android.content.BroadcastReceiver,android.content.IntentFilter,java.lang.String,android.os.Handler)"
)

val onCreateSubsignatures = setOf(
    "void onCreate(android.os.Bundle)",
    "void onCreate(android.os.Bundle,android.os.PersistableBundle)"
)
val startActivitySubsignatures = setOf(
    "void startActivity(android.content.Intent)",
    "void startActivity(android.content.Intent,android.os.Bundle)",
    "void startActivityForResult(android.content.Intent,int)",
    "void startActivityForResult(android.content.Intent,int,android.os.Bundle)",
    "void startActivities(android.content.Intent[],android.os.Bundle)",
    "void startActivities(android.content.Intent[])",
    "void startActivityFromChild(android.app.Activity,android.content.Intent,int,android.os.Bundle)",
    "void startActivityFromChild(android.app.Activity,android.content.Intent,int)",
    "void startActivityFromFragment(android.app.Fragment,android.content.Intent,int,android.os.Bundle)",
    "void startActivityFromFragment(android.app.Fragment,android.content.Intent,int)",
    "void startActivityIfNeeded(android.content.Intent,int,android.os.Bundle)",
    "void startActivityIfNeeded(android.content.Intent,int)"
)
val startActivityForResultSubsignatures = setOf(
    "void startActivityForResult(android.content.Intent,int)",
    "void startActivityForResult(android.content.Intent,int,android.os.Bundle)"
)

val componentNameConstructors = setOf(
    "<android.content.ComponentName: void <init>(java.lang.String,java.lang.String)>",
    "<android.content.ComponentName: void <init>(android.content.Context,java.lang.String)>",
    "<android.content.ComponentName: void <init>(android.content.Context,java.lang.Class)>"
)

val intentFilterConstructors = setOf(
    "<android.content.IntentFilter: void <init>(android.content.IntentFilter)>",
    "<android.content.IntentFilter: void <init>(java.lang.String)>",
    "<android.content.IntentFilter: void <init>(java.lang.String,java.lang.String)>",
    "<android.content.IntentFilter: android.content.IntentFilter create(java.lang.String,java.lang.String)>"
)
val intentFilterSetters = setOf(
    "<android.content.IntentFilter: void addAction(java.lang.String)>",
    "<android.content.IntentFilter: void addCategory(java.lang.String)>",
)
val intentConstructors = setOf(
//    "<android.content.Intent: void <init>()>",
    "<android.content.Intent: void <init>(android.content.Intent)>",
    "<android.content.Intent: void <init>(java.lang.String)>",
    "<android.content.Intent: void <init>(java.lang.String,android.net.Uri)>",
    "<android.content.Intent: void <init>(android.content.Context,java.lang.Class)>",
    "<android.content.Intent: void <init>(java.lang.String,android.net.Uri,android.content.Context,java.lang.Class)>"
)

val intentClassSetters = setOf(
    "<android.content.Intent: android.content.Intent setClass(android.content.Context,java.lang.Class)>",
    "<android.content.Intent: android.content.Intent setClassName(android.content.Context,java.lang.String)>",
    "<android.content.Intent: android.content.Intent setClassName(java.lang.String,java.lang.String)>",
    "<android.content.Intent: android.content.Intent setComponent(android.content.ComponentName)>"
//    "<android.content.Intent: android.content.Intent setAction(java.lang.String)>"
)
val javaClassMethodSignatures = setOf(
    "<java.lang.Class: java.lang.String getName()>",
    "<java.lang.Object: java.lang.Class getClass()>",
    "<java.lang.Class: java.lang.Class forName(java.lang.String)>"
)

val setActivityTitleSubsignatures = setOf(
    "void setTitle(java.lang.CharSequence)",
    "void setTitle(int)"
)
val uriMethodSignatures = setOf(
    "<android.net.Uri: android.net.Uri fromParts(java.lang.String,java.lang.String,java.lang.String)>",
    "<android.net.Uri: android.net.Uri parse(java.lang.String)>",
    "<android.net.Uri: android.net.Uri withAppendedPath(android.net.Uri,java.lang.String)>"
)

const val stringBuilderToString = "<java.lang.StringBuilder: java.lang.String toString()>"
val resourcesGetStringSignatures = setOf(
    "<android.content.res.Resources: java.lang.String getString(int)>",
    "<android.content.res.Resources: java.lang.String getText(int,java.lang.CharSequence)>",
    "<android.content.res.Resources: java.lang.CharSequence getText(int)>",
    "<android.content.res.Resources: java.lang.String[] getStringArray(int)>",
    "<android.content.res.Resources: java.lang.String[] getTextArray(int)>",
    "<android.content.Context: java.lang.String getString(int)>",
    "<android.app.Activity: java.lang.String getString(int)>"
)
val resourcesActivityGetStringSubsignatures = setOf(
    "java.lang.String getString(int)",
    "java.lang.CharSequence getText(int)",
    "java.lang.String getText(int,java.lang.CharSequence)"
)
val stringBuilderSignatures = setOf(
    "<java.lang.StringBuilder: java.lang.StringBuilder append(java.lang.String)>"
)
val stringBuilderSkipSignatures = setOf(
    "<java.lang.StringBuilder: java.lang.StringBuilder append(boolean)>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(char)>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(char[])>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(char[],int,int)>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(double)>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(float)>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(int)>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(long)>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(java.lang.Object)>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(java.lang.StringBuffer)>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(java.lang.CharSequence)>",
    "<java.lang.StringBuilder: java.lang.StringBuilder append(java.lang.CharSequence,int,int)>"
)

val stringFormatSignatures = setOf(
    "<java.lang.String: java.lang.String format(java.lang.String,java.lang.Object[])>",
    "<java.lang.String: java.lang.String format(java.util.Locale,java.lang.String,java.lang.Object[])>"
)
val stringManipulationInvokeSignatures = setOf(
    "<java.lang.String: java.lang.String toUpperCase()>",
    "<java.lang.String: java.lang.String toLowerCase()"
)
val stringManipulationAssignSignatures = setOf(
    "<java.lang.String: java.lang.String substring(int,int)>",
    "<java.lang.String: java.lang.String substring(int)>",
    "<java.lang.String: java.lang.String trim()>"
)
val androidHtmlSignatures = setOf(
    "<android.text.Html: android.text.Spanned escapeHtml(java.lang.CharSequence)>",
    "<android.text.Html: android.text.Spanned fromHtml(java.lang.String)>",
    "<android.text.Html: android.text.Spanned fromHtml(java.lang.String,int)>",
    "<android.text.Html: android.text.Spanned fromHtml(java.lang.String,android.text.Html\$ImageGetter,android.text.Html\$TagHandler)>",
    "<android.text.Html: android.text.Spanned fromHtml(java.lang.String,int,android.text.Html\$ImageGetter,android.text.Html\$TagHandler)>",
    "<android.text.Html: java.lang.String toHtml(android.text.Spanned,int)>",
    "<android.text.Html: java.lang.String toHtml(android.text.Spanned)>"
)


val addTransactionSubsignatures = setOf(
    "android.app.FragmentTransaction add(android.app.Fragment,java.lang.String)",
    "android.app.FragmentTransaction add(int,android.app.Fragment,java.lang.String)",
    "android.app.FragmentTransaction add(int,android.app.Fragment)",
    "android.support.v4.app.FragmentTransaction add(android.support.v4.app.Fragment,java.lang.String)",
    "android.support.v4.app.FragmentTransaction add(int,android.support.v4.app.Fragment,java.lang.String)",
    "android.support.v4.app.FragmentTransaction add(int,android.support.v4.app.Fragment)"
)

val replaceTransactionSubsignatures = setOf(
    "android.app.FragmentTransaction replace(int,android.app.Fragment,java.lang.String)",
    "android.app.FragmentTransaction replace(int,android.app.Fragment)",
    "android.support.v4.app.FragmentTransaction replace(int,android.support.v4.app.Fragment,java.lang.String)",
    "android.support.v4.app.FragmentTransaction replace(int,android.support.v4.app.Fragment)"
)

// root
val fragmentAddReplaceTransactionSubsignatures = addTransactionSubsignatures + replaceTransactionSubsignatures

val getFragmentManager = setOf(
    "android.support.v4.app.FragmentManager getSupportFragmentManager()",
    "android.app.FragmentManager getFragmentManager()"
)

val beginFragmentTransaction = setOf(
    "android.support.v4.app.FragmentTransaction beginTransaction()",
    "android.app.FragmentTransaction beginTransaction()"
)

val fragmentClasses = setOf("android.app.Fragment", "android.support.v4.app.Fragment", "androidx.fragment.app.Fragment")
val listFragmentClasses = setOf("android.app.ListFragment", "android.support.v4.app.ListFragment", "androidx.fragment.app.ListFragment")

val contentResolverSignatures = setOf(
    "<android.content.ContentResolver: android.database.Cursor query(android.net.Uri,java.lang.String[],android.os.Bundle,android.os.CancellationSignal)>",
    "<android.content.ContentResolver: android.database.Cursor query(android.net.Uri,java.lang.String[],java.lang.String,java.lang.String[],java.lang.String)>",
    "<android.content.ContentResolver: android.database.Cursor query(android.net.Uri,java.lang.String[],java.lang.String,java.lang.String[],java.lang.String,android.os.CancellationSignal)>",
    "<android.database.sqlite.SQLiteDatabase: android.database.Cursor query(android.net.Uri,java.lang.String[],java.lang.String,java.lang.String[],java.lang.String)>",
    "<android.database.sqlite.SQLiteDatabase: android.database.Cursor query(android.net.Uri,java.lang.String[],java.lang.String,java.lang.String[],java.lang.String,android.os.CancellationSignal)>"
//    "<android.support.v4.content.CursorLoader(android.content.Context,android.net.Uri,java.lang.String[],java.lang.String,java.lang.String[],java.lang.String)>",
//    "<android.content.CursorLoader(android.content.Context,android.net.Uri,java.lang.String[],java.lang.String,java.lang.String[],java.lang.String)>"
)
val startServiceSubsignatures = setOf(
    "android.content.ComponentName startService(android.content.Intent)"
)
val activityLifecycleSubsignatures = setOf(
    AndroidEntryPointConstants.ACTIVITY_ONCREATE,
    AndroidEntryPointConstants.ACTIVITY_ONDESTROY,
    AndroidEntryPointConstants.ACTIVITY_ONPAUSE,
    AndroidEntryPointConstants.ACTIVITY_ONRESTART,
    AndroidEntryPointConstants.ACTIVITY_ONRESUME,
    AndroidEntryPointConstants.ACTIVITY_ONSTART,
    AndroidEntryPointConstants.ACTIVITY_ONSTOP,
    AndroidEntryPointConstants.ACTIVITY_ONSAVEINSTANCESTATE,
    AndroidEntryPointConstants.ACTIVITY_ONRESTOREINSTANCESTATE,
    AndroidEntryPointConstants.ACTIVITY_ONCREATEDESCRIPTION,
    AndroidEntryPointConstants.ACTIVITY_ONPOSTCREATE,
    AndroidEntryPointConstants.ACTIVITY_ONPOSTRESUME
)

val serviceLifecycleSubsignatures = setOf(
    AndroidEntryPointConstants.SERVICE_ONCREATE,
    AndroidEntryPointConstants.SERVICE_ONDESTROY,
    AndroidEntryPointConstants.SERVICE_ONSTART1,
    AndroidEntryPointConstants.SERVICE_ONSTART2,
    AndroidEntryPointConstants.SERVICE_ONBIND,
    AndroidEntryPointConstants.SERVICE_ONREBIND,
    AndroidEntryPointConstants.SERVICE_ONUNBIND
)

val setTextSubsignatures = setOf(
    "void setText(int)",
    "void setText(java.lang.CharSequence)",
    "void setText(java.lang.CharSequence,android.widget.TextView.BufferType)",
    "void setText(int,android.widget.TextView.BufferType)",
//    "void setText(char[],int,int)",
    "void setTextKeepState(java.lang.CharSequence)",
    "void setTextKeepState(java.lang.CharSequence,android.widget.TextView.BufferType)",
    "void setHint(int)",
    "void setHint(java.lang.CharSequence)",
    "void setError(java.lang.CharSequence)",
    "void setError(java.lang.CharSequence,android.graphics.drawable.Drawable)",
    "void setContentDescription(java.lang.CharSequence)",
    "void setTooltipText(java.lang.CharSequence)",
    "void setTextOn(java.lang.CharSequence)",
    "void setTextOff(java.lang.CharSequence)"
)

val setInputTypeSubsignatures = setOf(
    "void setInputType(int)"
)
val setImageSubsignatures = setOf(
    "void setImageDrawable(android.graphics.drawable.Drawable)>",
    "void setImageResource(int)",
    "void setBackgroundDrawable(android.graphics.drawable.Drawable)",
    "void setBackground(android.graphics.drawable.Drawable)",
    "void setForeground(android.graphics.drawable.Drawable)"

)
val resourcesGetDrawableSignatures = setOf(
    "<android.content.res.Resources: android.graphics.drawable.Drawable getDrawable(int)>",
    "<android.content.res.Resources: android.graphics.drawable.Drawable getDrawable(int,android.content.res.Resources\$Theme)>",
    "<android.content.res.Resources: android.graphics.drawable.Drawable getDrawableForDensity(int,int))>",
    "<android.content.res.Resources: android.graphics.drawable.Drawable getDrawableForDensity(int,android.content.res.Resources\$Theme)>"
)
val getViewMethodSubsignature = "android.view.View getView(int,android.view.View,android.view.ViewGroup)"
val newViewMethodSubsignature = "android.view.View newView(android.content.Context,android.database.Cursor,android.view.ViewGroup)"
val bindViewMethodSubsignature = "void bindView(android.view.View,android.content.Context,android.database.Cursor)"

val getViewFragmentMethodSubsignature = "android.view.View getView()"

val adapterMethodSubsignatures = setOf( //FIXME: fragment.getView
    "android.view.View getView(int,android.view.View,android.view.ViewGroup)", //from Adapter
    "android.view.View newView(android.content.Context,android.database.Cursor,android.view.ViewGroup)", //from CursorAdapter
    "void bindView(android.view.View,android.content.Context,android.database.Cursor)" //from CursorAdapter
)

val fragmentGetActivity = setOf(
    "android.app.Activity getActivity()",
    "android.support.v4.app.FragmentActivity getActivity()"
)

//   <android.app.Activity: android.view.View findViewById(int)>
//   <android.app.View: android.view.View findViewById(int)>
val findViewByIdSubsignatures = setOf(
    "android.view.View findViewById(int)"
)
const val DUMMY_ACTIVITY_NAME = "dummyActivity"
const val HANDLER_CLASS_NAME = "android.os.Handler"
const val FIND_VIEW_METHOD_NAME = "findViewById"
const val VIEW_CONSTRUCTOR_SIGNATURE2 = "void <init>(android.content.Context)"
const val VIEW_CONSTRUCTOR_SIGNATURE = "void <init>(android.content.Context,android.util.AttributeSet)"
const val VIEW_SET_ID_SIGNATURE = "void setId(int)"
const val LISTENERS_FILE = "AndroidListeners.txt"
const val LISTENERS_NAME_FILE = "AndroidListenersNames.txt"
const val CALLBACK_FILE = "AndroidCallbacks.txt"
const val UI_CALLBACKS_FILE = "AndroidUICallbacks.txt"
const val SET_CONTENT_VIEW_SUBSIGNATURE = "void setContentView(int)"
const val DUMMY_VIEW_ID = -20
const val WRONG_STRING = "WRONG_STRING_ID:"

/** Inflators */
// unsupported yet:
// <android.view.LayoutInflator: android.view.View inflate(org.xmlpull.v1.XmlPullParser,android.view.ViewGroup)"
// <android.view.LayoutInflator: android.view.View inflate(org.xmlpull.v1.XmlPullParser,android.view.ViewGroup,boolean)"

val androidListeners = loadResource(LISTENERS_FILE)
val androidListenerNames = loadResource(LISTENERS_FILE)

/** Loads the set of interfaces that are used to implement Android callback handlers from a file on disk */
val androidCallbacks = loadResource(CALLBACK_FILE)
val uiCallbacks = loadResource(UI_CALLBACKS_FILE)

private fun loadResource(resourceFile: String): Set<String> {
    try {
        val systemClassLoader = ClassLoader.getSystemClassLoader()
        val inputStream: InputStream = systemClassLoader.getResourceAsStream(resourceFile)!!
        return inputStream.bufferedReader().lineSequence().toSet()
    } catch (e: IOException) {
        CallgraphAssembler.logger.error(e) { "Android listeners file is missing" }
        throw IllegalStateException("Android listeners file is missing", e)
    }
}

val ignoredViews = setOf(
    "android.widget.ProgressBar",
    "android.webkit.WebView"
)
val setContentViewSubsignatures = setOf(
    "void setContentView(int)",
    "void setContentView(android.view.View)",
    "void setContentView(android.view.View,android.view.ViewGroup\$LayoutParams)"
)
val setListAdapterSubsignature = setOf(
    "void setListAdapter(android.widget.ListAdapter)"
)

//root
val inflatorSubsignatures = setOf(
    "android.view.View inflate(int,android.view.ViewGroup)",
    "android.view.View inflate(int,android.view.ViewGroup,boolean)",
    "void inflate(int,android.view.ViewGroup,android.support.v4.view.AsyncLayoutInflater.OnInflateFinishedListener)",
    "android.view.View inflate(android.content.Context,int,android.view.ViewGroup)"
)

// databinding lib is not supported
// android.view.LayoutInflater
val layoutInflatorSignatures = setOf(
    "<android.view.LayoutInflater: android.view.View inflate(int,android.view.ViewGroup)>",
    "<android.view.LayoutInflater: android.view.View inflate(int,android.view.ViewGroup,boolean)>",
    "<android.support.v4.view.AsyncLayoutInflater: void inflate(int,android.view.ViewGroup,android.support.v4.view.AsyncLayoutInflater.OnInflateFinishedListener)>"
)

// static android.view.View
val viewInflatorSignature = setOf(
    "<android.view.View: android.view.View inflate(android.content.Context,int,android.view.ViewGroup)>"
)
val viewInflatorSubsignature = setOf(
    "android.view.View inflate(android.content.Context,int,android.view.ViewGroup)"
)

//MenuInflater(Context context)
//this.getMenuInflater()
val menuInflatorSignatures = setOf(
    "<android.view.MenuInflater: void inflate(int,android.view.Menu)>"
)
val adapterViewArguments = setOf(
    "android.view.View",
    "android.view.ViewGroup",
    "android.support.v7.widget.RecyclerView.Adapter"
)
val setAdapterMethodNames = setOf(
    "setAdapter",
    "setSuggestionsAdapter"
)
val setAdapterSubsignatures = setOf(
    "void setAdapter(android.widget.ListAdapter)",
    "void setSuggestionsAdapter(android.support.v4.widget.CursorAdapter)",
    "void setSuggestionsAdapter(androidx.cursoradapter.widget.CursorAdapter)"
)
val getListViewSubsignature = setOf(
    "android.widget.ListView getListView()"
)
val getIdSignatures = setOf(
    "<android.view.MenuItem: int getItemId()>",
    "<android.view.View: int getId()>"
)

val asyncMethodSubsignatures = setOf(
    "android.os.AsyncTask execute(java.lang.Object[])",
    "android.os.AsyncTask execute(java.lang.Runnable)",
    "android.os.AsyncTask executeOnExecutor(java.util.concurrent.Executor, Object[])",
    "android.os.AsyncTask android.os.AsyncTask executeOnExecutor(java.util.concurrent.Executor,java.lang.Object[])"

)

val addViewSubsignatures = setOf(
    "void addView(android.view.View,android.view.ViewGroup\$LayoutParams)",
    "void addView(android.view.View,int)",
    "void addView(android.view.View,int,android.view.ViewGroup\$LayoutParams",
    "void addView(android.view.View)",
    "void addView(android.view.View,int,int)",
    "void addHeaderView(android.view.View)"
)

val prefStringSignatures = setOf(
//    "java.lang.String getString(java.lang.String,java.lang.String)"
    "<android.content.SharedPreferences: java.lang.String getString(java.lang.String,java.lang.String)>"
)
const val defaultContainerId = 16908290 // android container id
const val xmlFragmentContainer = -10
const val dynamicFragmentId = -7
const val zeroContainerId = 0 // If 0, it will not be placed in a container.
val containerIds = setOf(
    16908290 //R.id.content android 17+
)

val listId = setOf(
    16908298 //R.id.list
)
const val STRING_ID_LENGTH = 10
//TODO addContentView

//TODO View.findViewsWithText, findViewWithTag, focusSearch, findFocus, getChildAt, getFocusedChild
// getCurrentView, getSelectedView
// Activity.getListView
//showMenu, showContextMenu, ShowOptionsMenuCalls

val onCreateMenuSubsignatures = setOf(
    "boolean onCreateOptionsMenu(android.view.Menu)",
    "void onCreateContextMenu(android.view.ContextMenu,android.view.View,android.view.ContextMenu.ContextMenuInfo)",
    "boolean onCreatePanelMenu(int,android.view.Menu)"
)

// onContextItemSelected(android.view.MenuItem)
// onOptionsItemSelected(MenuItem)
// TODO: process View#startActionMode(Callback) context menus
// TODO: process PopupMenu

val dialogBuilderClassNames = setOf(
    "android.app.AlertDialog\$Builder", // TODO: add custom dialogs
    "android.support.v7.app.AlertDialog\$Builder"
)
val dialogBuilderClasses = dialogBuilderClassNames.map { RefType.v(it) }

val dialogSetTitleSubsignatures = setOf(
    "void setTitle(java.lang.CharSequence)"
)
val dialogBuilderSetTitleSubsignatures = setOf(
    "android.app.AlertDialog\$Builder setTitle(java.lang.CharSequence)",
    "android.support.v7.app.AlertDialog\$Builder setTitle(java.lang.CharSequence)",
    "android.app.AlertDialog\$Builder setTitle(int)",
    "android.support.v7.app.AlertDialog\$Builder setTitle(int)"
)

val dialogSetMessageSubsignatures = setOf(
    "void setMessage(java.lang.CharSequence)"
)
val dialogBuilderSetMessageSubsignatures = setOf(
    "android.app.AlertDialog\$Builder setMessage(java.lang.CharSequence)",
    "android.support.v7.app.AlertDialog\$Builder setMessage(java.lang.CharSequence)",
    "android.app.AlertDialog\$Builder setMessage(int)",
    "android.support.v7.app.AlertDialog\$Builder setMessage(int)"
)
val dialogSetViewSubsignatures = setOf( //TODO: resolve setView
    "void setView(android.view.View)",
    "void setView(android.view.View,int,int,int,int)"
)
val dialogBuilderSetViewSubsignatures = setOf( //TODO: resolve setView
    "android.app.AlertDialog\$Builder setView(android.view.View)",
    "android.support.v7.app.AlertDialog\$Builder setView(android.view.View)",
    "android.app.AlertDialog\$Builder setView(android.view.View,int,int,int,int)",
    "android.support.v7.app.AlertDialog\$Builder setView(android.view.View,int,int,int,int)"
)
val dialogSetIconSubsignatures = setOf( //TODO: resolve setView
    "void setIcon(int)"
)
val dialogBuilderSetIconSubsignatures = setOf( //TODO: resolve setView
    "android.app.AlertDialog\$Builder setIcon(int)",
    "android.support.v7.app.AlertDialog\$Builder setIcon(int)"
)

// dialogs
// should process listeners, layout e.g. via setView or setContentView
val dialogSetButtonSubsignatures = setOf( //TODO
    "void setButton(int,java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)", //whichButton, text, listener
    "void setButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)", //text,listener
    "void setButton2(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)",
    "void setButton3(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)"
//    "void setButton(int,java.lang.CharSequence,android.os.Message)",
//    "void setButton(java.lang.CharSequence,android.os.Message)"
//    "void setButton2(java.lang.CharSequence,android.os.Message)",
//    "void setButton3(java.lang.CharSequence,android.os.Message)"
)

val dialogBuilderSetButtonSubsignatures = setOf(
    "android.app.AlertDialog\$Builder setPositiveButton(int,android.content.DialogInterface\$OnClickListener)", //textId,listener
    "android.support.v7.app.AlertDialog\$Builder setPositiveButton(int,android.content.DialogInterface\$OnClickListener)", //textId,listener
    "android.app.AlertDialog\$Builder setPositiveButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)", //text, listener
    "android.support.v7.app.AlertDialog\$Builder setPositiveButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)", //text, listener
    "android.app.AlertDialog\$Builder setNegativeButton(int,android.content.DialogInterface\$OnClickListener)", //textId, listener
    "android.support.v7.app.AlertDialog\$Builder setNegativeButton(int,android.content.DialogInterface\$OnClickListener)", //textId, listener
    "android.app.AlertDialog\$Builder setNegativeButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)",
    "android.support.v7.app.AlertDialog\$Builder setNegativeButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)",
    "android.app.AlertDialog\$Builder setNeutralButton(int,android.content.DialogInterface\$OnClickListener)",
    "android.support.v7.app.AlertDialog\$Builder setNeutralButton(int,android.content.DialogInterface\$OnClickListener)",
    "android.app.AlertDialog\$Builder setNeutralButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)",
    "android.support.v7.app.AlertDialog\$Builder setNeutralButton(java.lang.CharSequence,android.content.DialogInterface\$OnClickListener)"
//    "android.app.AlertDialog\$Builder setItems(int,android.content.DialogInterface\$OnClickListener)", //itemsId
//    "android.app.AlertDialog\$Builder setItems(java.lang.CharSequence[],android.content.DialogInterface\$OnClickListener)",
//    "android.app.AlertDialog\$Builder setAdapter(android.widget.ListAdapter,android.content.DialogInterface\$OnClickListener)",
//    "android.app.AlertDialog\$Builder setCursor(android.database.Cursor,android.content.DialogInterface\$OnClickListener,java.lang.String)",
//    "android.app.AlertDialog\$Builder setMultiChoiceItems(int,boolean[],android.content.DialogInterface\$OnMultiChoiceClickListener)",
//    "android.app.AlertDialog\$Builder setSingleChoiceItems(int,int,android.content.DialogInterface\$OnClickListener)",
//    "android.app.AlertDialog\$Builder setSingleChoiceItems(android.database.Cursor,int,java.lang.String,android.content.DialogInterface\$OnClickListener)",
//    "android.app.AlertDialog\$Builder setSingleChoiceItems(java.lang.CharSequence[],int,android.content.DialogInterface\$OnClickListener)",
//    "android.app.AlertDialog\$Builder setSingleChoiceItems(android.widget.ListAdapter,int,android.content.DialogInterface\$OnClickListener)",
//    "android.app.AlertDialog\$Builder setOnItemSelectedListener(android.widget.AdapterView\$OnItemSelectedListener)"
)

val dialogBuilderOtherSubsignatures = setOf(
    "android.app.AlertDialog\$Builder setCancelable(boolean)",
    "android.support.v7.app.AlertDialog\$Builder setCancelable(boolean)"
)

val makeToastSignatures = setOf(
    "<android.widget.Toast: android.widget.Toast makeText(android.content.Context,int,int)>",
    "<android.widget.Toast: android.widget.Toast makeText(android.content.Context,java.lang.CharSequence,int)"
    //setView(android.view.View)
)

val listAddSignatures = setOf("<java.util.List: boolean add(java.lang.Object)>")
