package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.terminal.EmulatorDebug;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSession.SessionChangedCallback;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends Activity implements ServiceConnection, OnClickListener {

    private static final int CONTEXTMENU_SELECT_URL_ID = 0;
    private static final int CONTEXTMENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXTMENU_PASTE_ID = 2;
    private static final int CONTEXTMENU_KILL_PROCESS_ID = 3;
    private static final int CONTEXTMENU_RESET_TERMINAL_ID = 4;
    private static final int CONTEXTMENU_STYLING_ID = 5;
    private static final int CONTEXTMENU_ZOOM_IN_ID = 6;
    private static final int CONTEXTMENU_ZOOM_OUT_ID = 7;
    private static final int CONTEXTMENU_SHOW_MENU_ID = 8;
    private static final int CONTEXTMENU_NEW_TAB_ID = 9;
    private static final int CONTEXTMENU_CHANGE_TAB_ID = 10;

    private static final int MAX_SESSIONS = 8;

    private static final String RELOAD_STYLE_ACTION = "com.termux.app.reload_style";

    /**
     * The main view of the activity showing the terminal. Initialized in onCreate().
     */
    @SuppressWarnings("NullableProblems")
    @NonNull
    TerminalView mTerminalView;

    TermuxPreferences mSettings;

    private LinearLayout mLlTopSwitch;
    private ImageView mIvAdd, mIvMenu;
    private ArrayList<EmulatorBean> mBeans = new ArrayList<>();
    private int INDEX = 0;

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermService;

    /**
     * Initialized in {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    ArrayAdapter<TerminalSession> mListViewAdapter;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    boolean mIsVisible;

    final SoundPool mBellSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();
    int mBellSoundId;

    private final BroadcastReceiver mBroadcastReceiever = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mIsVisible) {
                String whatToReload = intent.getStringExtra(RELOAD_STYLE_ACTION);
                if ("storage".equals(whatToReload)) {
                    //if (ensureStoragePermissionGranted())
                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                    return;
                }
                checkForFontAndColors();
                mSettings.reloadFromProperties(TermuxActivity.this);
            }
        }
    };

    void checkForFontAndColors() {
        try {
            @SuppressLint("SdCardPath") File fontFile = new File("/data/data/com.termux/files/home/.termux/font.ttf");
            @SuppressLint("SdCardPath") File colorsFile = new File("/data/data/com.termux/files/home/.termux/colors.properties");

            final Properties props = new Properties();
            if (colorsFile.isFile()) {
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props);
            TerminalSession session = getCurrentTermSession();
            if (session != null && session.getEmulator() != null) {
                session.getEmulator().mColors.reset();
            }
            updateBackgroundColor();

            final Typeface newTypeface = (fontFile.exists() && fontFile.length() > 0) ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
            mTerminalView.setTypeface(newTypeface);
        } catch (Exception e) {
            Log.e(EmulatorDebug.LOG_TAG, "Error in checkForFontAndColors()", e);
        }
    }

    void updateBackgroundColor() {
        TerminalSession session = getCurrentTermSession();
        if (session != null && session.getEmulator() != null) {
            getWindow().getDecorView().setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        }
    }

    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);

    private View addNewTopView() {
        View v = View.inflate(this, R.layout.tab_item, null);
        v.findViewById(R.id.close).setOnClickListener(this);
        v.findViewById(R.id.title).setOnClickListener(this);
        LinearLayout.LayoutParams temp = new LinearLayout.LayoutParams(
                0, 0, MAX_SESSIONS - mLlTopSwitch.getChildCount());
        mLlTopSwitch.addView(v, mLlTopSwitch.getChildCount() -1, params);
        mLlTopSwitch.findViewById(R.id.blank).setLayoutParams(temp);
        return v;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.iv_add:
                addNewSession(false);
                break;
            case R.id.iv_menu:
                mTerminalView.showContextMenu();
                break;
            case R.id.title:
                for (EmulatorBean bean : mBeans) {
                    if (bean.title == v) {
                        switchToSession(bean.session);
                        break;
                    }
                }
                break;
            case R.id.close:
                for (EmulatorBean bean : mBeans) {
                    if (bean.close == v) {
                        bean.session.finishIfRunning();
                        removeFinishedSession(bean.session);
                        break;
                    }
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        mSettings = new TermuxPreferences(this);

        setContentView(R.layout.drawer_layout);
        mLlTopSwitch = (LinearLayout) findViewById(R.id.ll_top_switch);
        mIvAdd = (ImageView) findViewById(R.id.iv_add);
        mIvMenu = (ImageView) findViewById(R.id.iv_menu);
        mIvAdd.setOnClickListener(this);
        mIvMenu.setOnClickListener(this);
        mTerminalView = (TerminalView) findViewById(R.id.terminal_view);
        mTerminalView.setOnKeyListener(new TermuxViewClient(this));

        mTerminalView.setTextSize(mSettings.getFontSize());
        mTerminalView.requestFocus();

        registerForContextMenu(mTerminalView);

        Intent serviceIntent = new Intent(this, TermuxService.class);
        // Start the service and make it run regardless of who is bound to it:
        startService(serviceIntent);
        if (!bindService(serviceIntent, this, 0))
            throw new RuntimeException("bindService() failed");

        checkForFontAndColors();

        mBellSoundId = mBellSoundPool.load(this, R.raw.bell, 1);
    }

    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermService = ((TermuxService.LocalBinder) service).service;

        mTermService.mSessionChangeCallback = new SessionChangedCallback() {
            @Override
            public void onTextChanged(TerminalSession changedSession) {
                if (!mIsVisible) return;
                if (getCurrentTermSession() == changedSession) mTerminalView.onScreenUpdated();
            }

            @Override
            public void onTitleChanged(TerminalSession updatedSession) {
                if (!mIsVisible) return;
                if (updatedSession != getCurrentTermSession()) {
                    // Only show toast for other sessions than the current one, since the user
                    // probably consciously caused the title change to change in the current session
                    // and don't want an annoying toast for that.
                }
            }

            @Override
            public void onSessionFinished(final TerminalSession finishedSession) {
                if (mTermService.mWantsToStop) {
                    // The service wants to stop as soon as possible.
                    finish();
                    return;
                }
                if (mIsVisible && finishedSession != getCurrentTermSession()) {
                    // Show toast for non-current sessions that exit.
                    int indexOfSession = mTermService.getSessions().indexOf(finishedSession);
                }
            }

            @Override
            public void onClipboardText(TerminalSession session, String text) {
                if (!mIsVisible) return;
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(text)));
            }

            @Override
            public void onBell(TerminalSession session) {
                if (!mIsVisible) return;

                switch (mSettings.mBellBehaviour) {
                    case TermuxPreferences.BELL_BEEP:
                        mBellSoundPool.play(mBellSoundId, 1.f, 1.f, 1, 0, 1.f);
                        break;
                    case TermuxPreferences.BELL_VIBRATE:
                        ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(50);
                        break;
                    case TermuxPreferences.BELL_IGNORE:
                        // Ignore the bell character.
                        break;
                }

            }

            @Override
            public void onColorsChanged(TerminalSession changedSession) {
                if (getCurrentTermSession() == changedSession) updateBackgroundColor();
            }
        };

        if (mTermService.getSessions().isEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupIfNeeded(TermuxActivity.this, new Runnable() {
                    @Override
                    public void run() {
                        if (mTermService == null) return; // Activity might have been destroyed.
                        try {
                            addNewSession(false);
                        } catch (WindowManager.BadTokenException e) {
                            // Activity finished - ignore.
                        }
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finish();
            }
        } else {
            Intent i = getIntent();
            if (i != null && Intent.ACTION_RUN.equals(i.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                addNewSession(false);
            } else {
                List<TerminalSession> sessions = mTermService.getSessions();
                for (TerminalSession s : sessions) {
                    View v = addNewTopView();
                    EmulatorBean bean = new EmulatorBean(v, s);
                    mBeans.add(bean);
                    ((TextView) bean.title).setText("Session " + (++INDEX));
                }
                switchToSession(getStoredCurrentSessionOrLast());
            }
        }
    }

    public void switchToSession(boolean forward) {
        TerminalSession currentSession = getCurrentTermSession();
        int index = mTermService.getSessions().indexOf(currentSession);
        if (forward) {
            if (++index >= mTermService.getSessions().size()) index = 0;
        } else {
            if (--index < 0) index = mTermService.getSessions().size() - 1;
        }
        switchToSession(mTermService.getSessions().get(index));
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Respect being stopped from the TermuxService notification action.
        finish();
    }

    @Nullable
    TerminalSession getCurrentTermSession() {
        return mTerminalView.getCurrentSession();
    }

    @Override
    public void onStart() {
        super.onStart();
        mIsVisible = true;

        if (mTermService != null) {
            // The service has connected, but data may have changed since we were last in the foreground.
            switchToSession(getStoredCurrentSessionOrLast());
        }

        registerReceiver(mBroadcastReceiever, new IntentFilter(RELOAD_STYLE_ACTION));

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal:
        mTerminalView.onScreenUpdated();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mIsVisible = false;
        TerminalSession currentSession = getCurrentTermSession();
        if (currentSession != null) TermuxPreferences.storeCurrentSession(this, currentSession);
        unregisterReceiver(mBroadcastReceiever);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTermService != null) {
            // Do not leave service with references to activity.
            mTermService.mSessionChangeCallback = null;
            mTermService = null;
        }
        unbindService(this);
    }

    void addNewSession(boolean failSafe) {
        if (mTermService.getSessions().size() >= MAX_SESSIONS) {
            new AlertDialog.Builder(this).setTitle(R.string.max_terminals_reached_title).setMessage(R.string.max_terminals_reached_message)
                .setPositiveButton(android.R.string.ok, null).show();
        } else {
            View v = addNewTopView();
            String executablePath = (failSafe ? "/system/bin/sh" : null);
            TerminalSession newSession = mTermService.createTermSession(executablePath, null, null, failSafe);
            EmulatorBean bean = new EmulatorBean(v, newSession);
            mBeans.add(bean);
            switchToSession(newSession);
            ((TextView) bean.title).setText("Session " + (++INDEX));
        }
    }

    /**
     * Try switching to session and note about it, but do nothing if already displaying the session.
     */
    void switchToSession(TerminalSession session) {
        if (session != getCurrentTermSession()) {
            changeTitle(getCurrentTermSession(), false);
        }
        changeTitle(session, true);
        if (mTerminalView.attachSession(session)) {
            updateBackgroundColor();
        }
    }

    void changeTitle(TerminalSession session, boolean isSelected) {
        for (EmulatorBean bean : mBeans) {
            if (bean.session == session) {
                bean.view.setSelected(isSelected);
                break;
            }
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentTermSession();
        if (currentSession == null) return;

        menu.add(Menu.NONE, CONTEXTMENU_SHOW_MENU_ID,
            Menu.NONE, R.string.show_menu);
        menu.add(Menu.NONE, CONTEXTMENU_NEW_TAB_ID,
            Menu.NONE, R.string.new_tab);
        menu.add(Menu.NONE, CONTEXTMENU_CHANGE_TAB_ID,
            Menu.NONE, R.string.change_tab);
        menu.add(Menu.NONE, CONTEXTMENU_SELECT_URL_ID,
            Menu.NONE, R.string.select_url);
        menu.add(Menu.NONE, CONTEXTMENU_PASTE_ID,
            Menu.NONE, R.string.paste);
        menu.add(Menu.NONE, CONTEXTMENU_RESET_TERMINAL_ID,
            Menu.NONE, R.string.reset_terminal);
        menu.add(Menu.NONE, CONTEXTMENU_ZOOM_IN_ID,
            Menu.NONE, R.string.zoom_in);
        menu.add(Menu.NONE, CONTEXTMENU_ZOOM_OUT_ID,
            Menu.NONE, R.string.zoom_out);
        menu.add(Menu.NONE, CONTEXTMENU_KILL_PROCESS_ID,
            Menu.NONE, getResources().getString(R.string.kill_process, getCurrentTermSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXTMENU_STYLING_ID,
            Menu.NONE, R.string.style_terminal);
    }

    /**
     * Hook system menu to show context menu instead.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    static LinkedHashSet<CharSequence> extractUrls(String text) {
        // Pattern for recognizing a URL, based off RFC 3986
        // http://stackoverflow.com/questions/5713558/detect-and-extract-url-from-a-string
        final Pattern urlPattern = Pattern.compile(
            "(?:^|[\\W])((ht|f)tp(s?)://|www\\.)" + "(([\\w\\-]+\\.)+?([\\w\\-.~]+/?)*" + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
        LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
        Matcher matcher = urlPattern.matcher(text);
        while (matcher.find()) {
            int matchStart = matcher.start(1);
            int matchEnd = matcher.end();
            String url = text.substring(matchStart, matchEnd);
            urlSet.add(url);
        }
        return urlSet;
    }

    void showUrlSelection() {
        String text = getCurrentTermSession().getEmulator().getScreen().getTranscriptText();
        LinkedHashSet<CharSequence> urlSet = extractUrls(text);
        if (urlSet.isEmpty()) {
            new AlertDialog.Builder(this).setMessage(R.string.select_url_no_found).show();
            return;
        }

        final CharSequence[] urls = urlSet.toArray(new CharSequence[urlSet.size()]);
        Collections.reverse(Arrays.asList(urls)); // Latest first.

        // Click to copy url to clipboard:
        final AlertDialog dialog = new AlertDialog.Builder(TermuxActivity.this).setItems(urls, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface di, int which) {
                String url = (String) urls[which];
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(url)));
            }
        }).setTitle(R.string.select_url_dialog_title).create();

        dialog.show();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentTermSession();

        switch (item.getItemId()) {
            case CONTEXTMENU_NEW_TAB_ID:
                addNewSession(false);
                return true;
            case CONTEXTMENU_SELECT_URL_ID:
                showUrlSelection();
                return true;
            case CONTEXTMENU_PASTE_ID:
                doPaste();
                return true;
            case CONTEXTMENU_KILL_PROCESS_ID:
                final AlertDialog.Builder b = new AlertDialog.Builder(this);
                b.setIcon(android.R.drawable.ic_dialog_alert);
                b.setMessage(R.string.confirm_kill_process);
                b.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                        getCurrentTermSession().finishIfRunning();
                    }
                });
                b.setNegativeButton(android.R.string.no, null);
                b.show();
                return true;
            case CONTEXTMENU_RESET_TERMINAL_ID:
                if (session != null) {
                    session.reset();
                }
                return true;
            case CONTEXTMENU_STYLING_ID:
                Intent stylingIntent = new Intent();
                stylingIntent.setClassName(this, "com.termux.styling.TermuxStyleActivity");
                startActivity(stylingIntent);
                return true;
            case CONTEXTMENU_ZOOM_IN_ID:
                changeFontSize(true);
                return true;
            case CONTEXTMENU_ZOOM_OUT_ID:
                changeFontSize(false);
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    void changeFontSize(boolean increase) {
        mSettings.changeFontSize(this, increase);
        mTerminalView.setTextSize(mSettings.getFontSize());
    }

    void doPaste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null) return;
        CharSequence paste = clipData.getItemAt(0).coerceToText(this);
        if (!TextUtils.isEmpty(paste))
            getCurrentTermSession().getEmulator().paste(paste.toString());
    }

    /**
     * The current session as stored or the last one if that does not exist.
     */
    public TerminalSession getStoredCurrentSessionOrLast() {
        TerminalSession stored = TermuxPreferences.getCurrentSession(this);
        if (stored != null) return stored;
        List<TerminalSession> sessions = mTermService.getSessions();
        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
    }

    public void removeFinishedSession(TerminalSession finishedSession) {
        // Return pressed with finished session - remove it.
        TermuxService service = mTermService;
        int index = service.removeTermSession(finishedSession);
        for (EmulatorBean bean : mBeans) {
            if (bean.session == finishedSession) {
                mLlTopSwitch.removeView(bean.view);
                LinearLayout.LayoutParams temp = new LinearLayout.LayoutParams(
                        0, 0, MAX_SESSIONS - mLlTopSwitch.getChildCount() + 1);
                mLlTopSwitch.findViewById(R.id.blank).setLayoutParams(temp);
                mBeans.remove(bean);
                break;
            }
        }

        if (mTermService.getSessions().isEmpty()) {
            // There are no sessions to show, so finish the activity.
            finish();
        } else {
            if (index >= service.getSessions().size()) {
                index = service.getSessions().size() - 1;
            }
            switchToSession(service.getSessions().get(index));
        }
    }

}
