package com.geri.psiwest;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Simple screen with setup instructions. Opening it also refreshes any placed widgets. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad * 2, pad, pad);
        tv.setTextSize(17);
        tv.setText(R.string.instructions);
        setContentView(tv);
        PsiWidgetProvider.requestRefresh(this);
    }
}
