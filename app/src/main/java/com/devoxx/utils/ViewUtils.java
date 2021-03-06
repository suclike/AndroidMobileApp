package com.devoxx.utils;

import org.androidannotations.annotations.EBean;
import org.androidannotations.annotations.RootContext;

import android.content.Context;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

@EBean
public class ViewUtils {

	@RootContext
	Context context;

	public boolean setTextOrHide(final TextView view, final CharSequence text) {
		if (TextUtils.isEmpty(text)) {
			view.setVisibility(View.GONE);
			view.setText(null);
			return false;
		} else {
			view.setVisibility(View.VISIBLE);
			view.setText(text);
			return true;
		}
	}

	public int getStatusBarHeight() {
		int result = 0;
		final int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
		if (resourceId > 0) {
			result = context.getResources().getDimensionPixelSize(resourceId);
		}
		return result;
	}

	public void hideFab(FloatingActionButton fab) {
		final CoordinatorLayout.LayoutParams p = (CoordinatorLayout.LayoutParams) fab.getLayoutParams();
		p.setAnchorId(View.NO_ID);
		fab.setLayoutParams(p);
		fab.setVisibility(View.GONE);
	}

	public void setFabMargins(FloatingActionButton fab, int left, int top, int right, int bottom) {
		CoordinatorLayout.LayoutParams p = (CoordinatorLayout.LayoutParams) fab.getLayoutParams();
		p.leftMargin = left;
		p.topMargin = top;
		p.rightMargin = right;
		p.bottomMargin = bottom;
		fab.setLayoutParams(p);
	}
}
