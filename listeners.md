onClick()                View.OnClickListener
onLongClick()            View.OnLongClickListener
onFocusChange()            View.OnFocusChangeListener
onKey()                    View.OnKeyListener
onTouch()                View.OnTouchListener
onMenuItemClick()        View.OnMenuItemClickListener
onCreateContextMenu()    View.OnCreateContextMenuListener
onTouchEvent()
onItemLongClick()        AdapterView.OnItemLongClickListener

onItemSelected()
onOptionsItemSelected()
onContextItemSelected()
onActionItemClicked()
onClick()                OnMultiChoiceClickListener()

onKeyDown()
onKeyUp()
onKeyLongPress()
onKeyMultiple()
onTrackballEvent()
onTouchEvent()
onFocusChanged()


Activity
onBackPressed
onContextMenuClosed
onOptionsMenuClosed
onTouchEvent
onUserInteraction
onMenuOpened
onNavigateUp
onSearchRequested
onTouchEvent
onTrackballEvent

Buttons
on click events

Text Fileds
keyboard actions
in xml:
    android:imeOptions="actionSend"
onEditorAction()        TextView.OnEditorActionListener 

Checkboxes
Radio Buttons
Toggle Buttons
onClick in xml

Spinners
onItemSelected()         AdapterView.OnItemSelectedListener inside it-> onItemSelected() and onNothingSelected()

Pickers
based on dialogs
built-in pick a time or pick a date dialogs
e.g. onTimeSet()             TimePickerDialog.OnTimeSetListener
     onDateSet()            DatePickerDialog.OnDateSetListener
custom pickers???

Dialogs (incomplete)
interface NoticeDialogListener {
        public void onDialogPositiveClick(DialogFragment dialog);
        public void onDialogNegativeClick(DialogFragment dialog);
    }

Notifications
if you want to start Activity when the user clicks the notification text in the notification drawer, you add the PendingIntent by calling setContentIntent()
https://androidlearnersite.wordpress.com/2018/02/23/communicating-from-services/

ActionMode.Callback
onActionItemClicked() any time a contextual action button is clicked


Some other methods that you should be aware of, which are not part of the View class, but can directly impact the way able to handle events.
Activity.dispatchTouchEvent(MotionEvent) - This allows your Activity to intercept all touch events before they are dispatched to the window.
ViewGroup.onInterceptTouchEvent(MotionEvent) - This allows a ViewGroup to watch events as they are dispatched to child Views.
ViewParent.requestDisallowInterceptTouchEvent(boolean) - Call this upon a parent View to indicate that it should not intercept touch events with onInterceptTouchEvent(MotionEvent)


Ways to register an event listener  https://github.com/IanDarwin/Android-Cookbook-Examples/blob/master/EventListenersDemo/src/biz/tekeye/listeners/main.java  

Most used: 
1) Anonymous Inner Class
```
b1=(Button)findViewById(R.id.button);
b1.setOnClickListener(new View.OnClickListener() {
         @Override
         public void onClick(View v) {
            TextView txtView = (TextView) findViewById(R.id.textView);
            txtView.setTextSize(25);
         }
      });
```
2) Activity class implements the Listener interface.
```
public class main extends Activity implements OnClickListener{
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        findViewById(R.id.button1).setOnClickListener(this);
    }    
    public void onClick(View arg0) {
    Button btn = (Button)arg0;
    TextView tv = (TextView) findViewById(R.id.textview1);
    tv.setText("You pressed " + btn.getText());
    }
}
```
3) member class
```
public class main extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        //attach an instance of HandleClick to the Button
        findViewById(R.id.button1).setOnClickListener(new HandleClick());
    }    
    private class HandleClick implements OnClickListener{
        public void onClick(View arg0) {
        Button btn = (Button)arg0;    //cast view to a button
        // get a reference to the TextView
        TextView tv = (TextView) findViewById(R.id.textview1);
        // update the TextView text
        tv.setText("You pressed " + btn.getText());
    }
    }
}
```
4) interface type
```
public class main extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        //use the handleClick variable to attach the event listener
        findViewById(R.id.button1).setOnClickListener(handleClick);
    }    
    private OnClickListener handleClick = new OnClickListener(){
        public void onClick(View arg0) {
        Button btn = (Button)arg0;
        TextView tv = (TextView) findViewById(R.id.textview1);
        tv.setText("You pressed " + btn.getText());
    }
    };
}
```
5) activity_main.xml file (only applies to the onClick event)
```
public class main extends Activity{
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }    
    public void HandleClick(View arg0) {
    Button btn = (Button)arg0;
        TextView tv = (TextView) findViewById(R.id.textview1);
    tv.setText("You pressed " + btn.getText());
    }
}
```
In the layout file the Button would be declared with the android:onClick attribute.
```
<Button android:id="@+id/button1"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Button 1"
        android:onClick="HandleClick"/>
```

No set<listener> case:
```
AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
           public void onClick(DialogInterface dialog, int id) {
               // User clicked OK button
           }
       });

builder.setTitle(R.string.pick_color)
       .setItems(R.array.colors_array, new DialogInterface.OnClickListener() {
           public void onClick(DialogInterface dialog, int which) {
           // The 'which' argument contains the index position
           // of the selected item
       }
    });
```

one method may handle several UI items like this:
```
    public void onCheckboxClicked(View view) {
        // Is the view now checked?
        boolean checked = ((CheckBox) view).isChecked();
        
        // Check which checkbox was clicked
        switch(view.getId()) {
            case R.id.checkbox_meat:
                if (checked)
                    // Put some meat on the sandwich
                else
                    // Remove the meat
                break;
            case R.id.checkbox_cheese:
                if (checked)
                    // Cheese me
                else
                    // I'm lactose intolerant
                break;
            // TODO: Veggie sandwich
        }
    }
```
Another example of implicitly defining callback
```
listViewData.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (mActionMode != null) {
            return false;
        }

        mActionMode = ((ActionBarActivity) getActivity()).startSupportActionMode(mActionModeCallback);
        view.setSelected(true);
        return true;
    }
});
private ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {
    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        ...
    }    
};
```