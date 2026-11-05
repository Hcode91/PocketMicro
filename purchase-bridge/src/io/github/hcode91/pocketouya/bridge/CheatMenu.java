package io.github.hcode91.pocketouya.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Opt-in cheat menu for single-player games, shown from the in-game touch controls.
 *
 * <p>Works like a basic Cheat Engine: search memory for a value, narrow the results as the value
 * changes in the game, then edit or freeze it. Cheats are saved per game. Only installed when the
 * user ticks "Cheat menu" while converting the game (an {@code assets/pocketouya/cheats} marker).
 */
public final class CheatMenu {
    static final String MARKER="pocketouya/cheats";
    private static final String TAG="PocketOuyaCheats";
    private static final String PREFS="pocketouya_cheats_v1";
    private static final int SHOWN=50;
    private static Boolean enabled;

    /** Whether this game was converted with the cheat menu ticked. */
    public static boolean enabled(Activity activity){
        if(enabled==null){
            try{activity.getAssets().open(MARKER).close();enabled=true;}catch(Exception absent){enabled=false;}
        }
        return enabled;
    }

    static final class Cheat {
        String label;long address;MemoryScanner.Type type;double value;boolean frozen;
        String library;long offset; // set when the value sits in a library's data, which stays put between launches
    }

    private static MemoryScanner scanner;
    private static final List<Cheat> cheats=new ArrayList<>();
    private static boolean loaded;
    private static volatile boolean busy,cancel;
    private static Thread freezer;

    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status;
    private LinearLayout results,saved;
    private Spinner typeSpinner,compareSpinner;
    private EditText valueField;
    private AlertDialog dialog;
    private ScrollView scroll;
    // Rows on screen, so the periodic refresh only changes their text: rebuilding them made the
    // window jump back to the top every refresh.
    private final List<TextView> resultRows=new ArrayList<>();
    private final List<long[]> resultAddresses=new ArrayList<>();
    private final Map<Cheat,TextView> cheatRows=new LinkedHashMap<>();
    private final Runnable refresh=new Runnable(){public void run(){if(dialog==null||!dialog.isShowing())return;if(!busy)refreshValues();handler.postDelayed(this,800);}};

    private CheatMenu(Activity activity){this.activity=activity;}

    public static void open(Activity activity){
        try{new CheatMenu(activity).show();}
        catch(RuntimeException|LinkageError error){Log.e(TAG,"Cheat menu unavailable",error);Toast.makeText(activity,"Cheat menu could not open. See the PocketOuyaCheats log.",Toast.LENGTH_LONG).show();}
    }

    private void show(){
        if(scanner==null)scanner=new MemoryScanner(activity.getCacheDir());
        load();
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(8),dp(16),dp(8));

        heading(content,"Search");
        typeSpinner=new Spinner(activity);
        List<String> types=new ArrayList<>();for(MemoryScanner.Type t:MemoryScanner.Type.values())types.add(t.label);
        typeSpinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,types));
        typeSpinner.setSelection(scanner.type().ordinal());content.addView(typeSpinner);

        LinearLayout valueRow=new LinearLayout(activity);valueRow.setGravity(Gravity.CENTER_VERTICAL);
        compareSpinner=new Spinner(activity);
        List<String> compares=new ArrayList<>();for(MemoryScanner.Compare c:MemoryScanner.Compare.values())compares.add(c.label);
        compareSpinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,compares));
        valueRow.addView(compareSpinner,new LinearLayout.LayoutParams(0,-2,1));
        valueField=new EditText(activity);valueField.setHint("Value");valueField.setSingleLine(true);
        valueField.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        valueRow.addView(valueField,new LinearLayout.LayoutParams(0,-2,1));
        content.addView(valueRow);

        LinearLayout buttons=new LinearLayout(activity);
        buttons.addView(action("New search",v->newSearch()),weight());
        buttons.addView(action("Next search",v->nextSearch()),weight());
        content.addView(buttons);
        LinearLayout more=new LinearLayout(activity);
        more.addView(action("Unknown value",v->unknownSearch()),weight());
        more.addView(action("Stop / clear",v->{if(busy)cancel=true;else{scanner.reset();status("Results cleared.");showResults();}}),weight());
        content.addView(more);

        status=new TextView(activity);status.setPadding(0,dp(6),0,dp(6));content.addView(status);
        results=new LinearLayout(activity);results.setOrientation(LinearLayout.VERTICAL);content.addView(results);

        heading(content,"Saved cheats");
        saved=new LinearLayout(activity);saved.setOrientation(LinearLayout.VERTICAL);content.addView(saved);

        TextView help=new TextView(activity);help.setTextSize(12);help.setPadding(0,dp(12),0,0);
        help.setText("How to use: search for a number the game shows (for example coins: 250). Change it in the game, then pick \"Equal to\" and search the new number with Next search. "
                +"Repeat until a few results are left, then tap one to save it as a cheat. For values with no number on screen (a health bar), start with Unknown value and narrow with Increased or Decreased.\n\n"
                +"For single-player play. Don't use it on purchases or anything paid.");
        content.addView(help);

        scroll=new ScrollView(activity);scroll.addView(content);
        // Typing in the value box shouldn't pull the list back up when rows change.
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        dialog=new AlertDialog.Builder(activity).setTitle("Cheats").setView(scroll).setPositiveButton("Close",null).create();
        dialog.setOnDismissListener(d->handler.removeCallbacks(refresh));
        dialog.show();
        String unavailable=MemoryScanner.unavailableReason();
        if(unavailable!=null)status(unavailable);
        else if(busy)status("A search is still running…");
        else status(describe());
        showResults();showCheats();
        handler.postDelayed(refresh,800);
    }

    private MemoryScanner.Type selectedType(){return MemoryScanner.Type.values()[typeSpinner.getSelectedItemPosition()];}
    private MemoryScanner.Compare selectedCompare(){return MemoryScanner.Compare.values()[compareSpinner.getSelectedItemPosition()];}

    private Double typedValue(){
        try{return Double.parseDouble(valueField.getText().toString().trim().replace(',','.'));}
        catch(NumberFormatException error){Toast.makeText(activity,"Type a number first",Toast.LENGTH_SHORT).show();return null;}
    }

    private void newSearch(){
        if(busy)return;
        Double value=typedValue();if(value==null)return;
        MemoryScanner.Type type=selectedType();
        run(()->{int n=scanner.first(type,value,progress());return n+" result"+(n==1?"":"s")+" for "+MemoryScanner.format(value,type)+". Change it in the game, then use Next search.";});
    }

    private void unknownSearch(){
        if(busy)return;
        MemoryScanner.Type type=selectedType();
        run(()->{long bytes=scanner.snapshot(type,progress());return "Remembered "+(bytes>>20)+" MB. Change the value in the game, then pick Increased, Decreased or Changed and use Next search.";});
    }

    private void nextSearch(){
        if(busy)return;
        if(!scanner.hasResults()){Toast.makeText(activity,"Start with New search or Unknown value",Toast.LENGTH_SHORT).show();return;}
        MemoryScanner.Compare compare=selectedCompare();
        if(compare==MemoryScanner.Compare.EQUALS&&scanner.waitingForUnknownCompare()){Toast.makeText(activity,"After Unknown value, compare with Increased, Decreased, Changed or Unchanged",Toast.LENGTH_LONG).show();return;}
        double value=0;
        if(compare.needsValue()){Double typed=typedValue();if(typed==null)return;value=typed;}
        final double v=value;
        run(()->{int n=scanner.next(compare,v,progress());return n==0?"No results left. Start a new search.":n+" result"+(n==1?"":"s")+" left."+(n>SHOWN?" Keep narrowing.":" Tap one to save it.");});
    }

    private interface Search{String run()throws Exception;}

    private void run(Search search){
        busy=true;cancel=false;results.removeAllViews();status("Searching…");
        new Thread(()->{
            String message;
            try{message=search.run();}
            catch(Exception|OutOfMemoryError error){message=error instanceof java.io.InterruptedIOException?"Search stopped.":"Search failed: "+error.getMessage();Log.w(TAG,"Search failed",error);if(error instanceof OutOfMemoryError)scanner.reset();}
            busy=false;final String done=message;
            handler.post(()->{status(done);showResults();});
        },"pocketouya-cheat-search").start();
    }

    private MemoryScanner.Progress progress(){
        return new MemoryScanner.Progress(){
            long last;
            public void update(String message){long now=System.currentTimeMillis();if(now-last<200)return;last=now;handler.post(()->status(message));}
            public boolean cancelled(){return cancel;}
        };
    }

    private String describe(){
        if(scanner.waitingForUnknownCompare())return "Memory remembered. Change the value in the game, then use Next search.";
        int n=scanner.count();
        return n==0?"Pick the kind of number, type the value the game shows, then New search.":n+" result"+(n==1?"":"s")+".";
    }

    private void showResults(){
        if(results==null)return;
        int y=scroll==null?0:scroll.getScrollY();
        results.removeAllViews();resultRows.clear();resultAddresses.clear();
        int n=scanner.count();
        if(n>0&&n<=SHOWN&&!busy){
            MemoryScanner.Type type=scanner.type();
            for(int i=0;i<n;i++){
                final long address=scanner.address(i);
                TextView row=new TextView(activity);row.setPadding(0,dp(8),0,dp(8));
                row.setText(resultText(address,type));
                row.setOnClickListener(v->saveDialog(address,type,scanner.read(address,type)));
                results.addView(row);resultRows.add(row);resultAddresses.add(new long[]{address});
            }
        }
        keepScroll(y);
    }

    private String resultText(long address,MemoryScanner.Type type){
        Double current=scanner.read(address,type);
        return String.format(Locale.US,"%08X  =  %s",address,current==null?"(gone)":MemoryScanner.format(current,type));
    }

    private String cheatText(Cheat c){
        Double current=scanner.read(c.address,c.type);
        return c.label+"  =  "+(current==null?"(not found)":MemoryScanner.format(current,c.type))
                +"\n"+c.type.label+(c.frozen?" · frozen at "+MemoryScanner.format(c.value,c.type):"");
    }

    /** Updates the numbers shown without rebuilding the rows. */
    private void refreshValues(){
        MemoryScanner.Type type=scanner.type();
        for(int i=0;i<resultRows.size();i++){String t=resultText(resultAddresses.get(i)[0],type);if(!t.contentEquals(resultRows.get(i).getText()))resultRows.get(i).setText(t);}
        for(Map.Entry<Cheat,TextView> e:cheatRows.entrySet()){String t=cheatText(e.getKey());if(!t.contentEquals(e.getValue().getText()))e.getValue().setText(t);}
    }

    private void keepScroll(int y){if(scroll!=null)scroll.post(()->scroll.scrollTo(0,y));}

    private void saveDialog(long address,MemoryScanner.Type type,Double current){
        EditText name=new EditText(activity);name.setHint("Name, e.g. Coins");name.setSingleLine(true);
        new AlertDialog.Builder(activity).setTitle("Save cheat").setView(padded(name))
            .setPositiveButton("Save",(d,w)->{
                Cheat c=new Cheat();c.label=name.getText().toString().trim();if(c.label.isEmpty())c.label=String.format(Locale.US,"%08X",address);
                c.address=address;c.type=type;c.value=current==null?0:current;
                String[] where=scanner.locate(address);if(where!=null){c.library=where[0];c.offset=Long.parseLong(where[1]);}
                synchronized(cheats){cheats.add(c);}store();showCheats();
            }).setNegativeButton("Cancel",null).show();
    }

    private void showCheats(){
        if(saved==null)return;
        int y=scroll==null?0:scroll.getScrollY();
        saved.removeAllViews();cheatRows.clear();
        if(cheats.isEmpty()){TextView none=new TextView(activity);none.setText("None yet.");saved.addView(none);keepScroll(y);return;}
        List<Cheat> copy;synchronized(cheats){copy=new ArrayList<>(cheats);}
        for(final Cheat c:copy){
            LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView text=new TextView(activity);
            text.setText(cheatText(c));cheatRows.put(c,text);
            text.setOnClickListener(v->editDialog(c));
            row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            CheckBox freeze=new CheckBox(activity);freeze.setText("Freeze");freeze.setChecked(c.frozen);
            freeze.setOnCheckedChangeListener((b,on)->{
                if(on){Double now=scanner.read(c.address,c.type);if(now==null){b.setChecked(false);Toast.makeText(activity,"That value isn't in memory now",Toast.LENGTH_SHORT).show();return;}if(!c.frozen)c.value=now;}
                c.frozen=on;store();startFreezer();
                TextView shown=cheatRows.get(c);if(shown!=null)shown.setText(cheatText(c));
            });
            row.addView(freeze);
            saved.addView(row);
        }
        keepScroll(y);
    }

    private void editDialog(Cheat c){
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);
        EditText name=new EditText(activity);name.setText(c.label);name.setSingleLine(true);form.addView(name);
        EditText value=new EditText(activity);value.setHint("New value");value.setSingleLine(true);
        value.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Double current=scanner.read(c.address,c.type);if(current!=null)value.setText(MemoryScanner.format(current,c.type));
        form.addView(value);
        TextView where=new TextView(activity);where.setTextSize(12);
        where.setText(c.library!=null?"Stored in "+new java.io.File(c.library).getName()+": usually found again after restarting the game."
                :"Stored in moving memory: after restarting the game it may point somewhere else. Search again if the value looks wrong.");
        form.addView(where);
        new AlertDialog.Builder(activity).setTitle("Edit cheat").setView(padded(form))
            .setPositiveButton("Set value",(d,w)->{
                c.label=name.getText().toString().trim().isEmpty()?c.label:name.getText().toString().trim();
                try{
                    double v=Double.parseDouble(value.getText().toString().trim().replace(',','.'));
                    if(scanner.write(c.address,c.type,v)){c.value=v;Toast.makeText(activity,c.label+" set to "+MemoryScanner.format(v,c.type),Toast.LENGTH_SHORT).show();}
                    else Toast.makeText(activity,"Couldn't write that value",Toast.LENGTH_LONG).show();
                }catch(NumberFormatException ignored){}
                store();showCheats();
            })
            .setNeutralButton("Delete",(d,w)->{synchronized(cheats){cheats.remove(c);}store();showCheats();})
            .setNegativeButton("Cancel",null).show();
    }

    private static synchronized void startFreezer(){
        if(freezer!=null)return;
        freezer=new Thread(()->{
            while(true){
                List<Cheat> frozen=new ArrayList<>();
                synchronized(cheats){for(Cheat c:cheats)if(c.frozen)frozen.add(c);}
                for(Cheat c:frozen)if(!scanner.write(c.address,c.type,c.value)){c.frozen=false;Log.w(TAG,"Unfroze "+c.label+": address no longer writable");}
                try{Thread.sleep(100);}catch(InterruptedException stop){return;}
            }
        },"pocketouya-cheat-freeze");
        freezer.setDaemon(true);freezer.start();
    }

    private void load(){
        if(loaded)return;loaded=true;
        try{
            JSONArray list=new JSONArray(prefs().getString("list","[]"));
            for(int i=0;i<list.length();i++){
                JSONObject o=list.getJSONObject(i);Cheat c=new Cheat();
                c.label=o.getString("label");c.type=MemoryScanner.Type.valueOf(o.getString("type"));c.value=o.getDouble("value");
                c.address=o.getLong("address");
                if(o.has("library")){c.library=o.getString("library");c.offset=o.getLong("offset");long a=scanner.resolve(c.library,c.offset);if(a>=0)c.address=a;}
                c.frozen=false; // never write to remembered addresses until the player turns a cheat back on
                synchronized(cheats){cheats.add(c);}
            }
        }catch(Exception error){Log.w(TAG,"Saved cheats unreadable",error);}
    }

    private void store(){
        try{
            JSONArray list=new JSONArray();
            synchronized(cheats){
                for(Cheat c:cheats){
                    JSONObject o=new JSONObject().put("label",c.label).put("type",c.type.name()).put("value",c.value).put("address",c.address);
                    if(c.library!=null)o.put("library",c.library).put("offset",c.offset);
                    list.put(o);
                }
            }
            prefs().edit().putString("list",list.toString()).apply();
        }catch(Exception error){Log.w(TAG,"Couldn't save cheats",error);}
    }

    private SharedPreferences prefs(){return activity.getSharedPreferences(PREFS,Activity.MODE_PRIVATE);}
    private void status(String message){if(status!=null)status.setText(message);}
    private void heading(LinearLayout parent,String text){TextView t=new TextView(activity);t.setText(text);t.setTextSize(16);t.setPadding(0,dp(10),0,dp(4));t.setTypeface(null,android.graphics.Typeface.BOLD);parent.addView(t);}
    private Button action(String text,View.OnClickListener click){Button b=new Button(activity);b.setText(text);b.setAllCaps(false);b.setOnClickListener(click);return b;}
    private static LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);}
    private View padded(View v){FrameLayout f=new FrameLayout(activity);f.setPadding(dp(20),dp(8),dp(20),0);f.addView(v);return f;}
    private int dp(int value){return (int)(value*activity.getResources().getDisplayMetrics().density+0.5f);}
}
