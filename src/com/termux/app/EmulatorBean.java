package com.termux.app;

import android.view.View;

import com.termux.R;
import com.termux.terminal.TerminalSession;

public class EmulatorBean {
    public View view;
    public TerminalSession session;
    public View title;
    public View close;

    public EmulatorBean(View view, TerminalSession session) {
        this.view = view;
        this.session = session;
        title = view.findViewById(R.id.title);
        close = view.findViewById(R.id.close);
    }

}
