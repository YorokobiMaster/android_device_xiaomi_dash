// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.widget.SettingsSpinnerAdapter;

import java.util.ArrayList;
import java.util.List;

/** Main list: a filter spinner row that scrolls with the per-app rows below it. */
final class AppListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_FILTER = 0;
    private static final int VIEW_TYPE_APP = 1;

    interface Listener {
        void onAppClick(AppEntry entry);

        /** Called from onBindViewHolder for icons that are not cached yet. */
        void onIconNeeded(AppEntry entry);

        /** Called from onBindViewHolder for rows whose stock group is unknown. */
        void onPolicyNeeded(AppEntry entry);

        /** Called when the filter spinner changes selection; the id is the label index. */
        void onFilterSelected(int filter);
    }

    private final Listener mListener;
    private final List<CharSequence> mFilterLabels;
    private final List<AppEntry> mItems = new ArrayList<>();

    private boolean mFilterVisible;
    private int mSelectedFilter;
    private SettingsSpinnerAdapter<CharSequence> mSpinnerAdapter;

    AppListAdapter(Listener listener, List<CharSequence> filterLabels) {
        mListener = listener;
        mFilterLabels = filterLabels;
    }

    void setItems(List<AppEntry> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    void setFilterVisible(boolean visible) {
        if (mFilterVisible == visible) {
            return;
        }
        mFilterVisible = visible;
        if (visible) {
            notifyItemInserted(0);
        } else {
            notifyItemRemoved(0);
        }
    }

    int indexOf(String packageName) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).packageName.equals(packageName)) {
                return i + (mFilterVisible ? 1 : 0);
            }
        }
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        return mFilterVisible && position == 0 ? VIEW_TYPE_FILTER : VIEW_TYPE_APP;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_FILTER) {
            return new FilterViewHolder(
                    inflater.inflate(R.layout.item_filter_spinner, parent, false));
        }
        return new ViewHolder(inflater.inflate(R.layout.item_app, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder.getItemViewType() == VIEW_TYPE_FILTER) {
            bindFilterRow((FilterViewHolder) holder);
            return;
        }
        final ViewHolder appHolder = (ViewHolder) holder;
        final AppEntry entry = mItems.get(position - (mFilterVisible ? 1 : 0));
        appHolder.label.setText(entry.label);
        final CharSequence subtitle = buildSubtitle(appHolder.itemView, entry);
        appHolder.subtitle.setText(subtitle);
        appHolder.subtitle.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);

        final Drawable icon = IconCache.get(appHolder.itemView.getContext(), entry.packageName);
        if (icon != null) {
            appHolder.icon.setImageDrawable(icon);
        } else {
            appHolder.icon.setImageDrawable(IconCache.getDefault(appHolder.itemView.getContext()));
            mListener.onIconNeeded(entry);
        }

        if (entry.overrideProfile == -1 && entry.stockGroup == null && !entry.policyRequested) {
            mListener.onPolicyNeeded(entry);
        }

        appHolder.itemView.setOnClickListener(v -> mListener.onAppClick(entry));
    }

    @Override
    public int getItemCount() {
        return mItems.size() + (mFilterVisible ? 1 : 0);
    }

    private void bindFilterRow(FilterViewHolder holder) {
        if (mSpinnerAdapter == null) {
            mSpinnerAdapter = new SettingsSpinnerAdapter<>(holder.spinner.getContext());
            mSpinnerAdapter.addAll(mFilterLabels);
        }
        mSpinnerAdapter.setSelectedPosition(mSelectedFilter);
        holder.spinner.setAdapter(mSpinnerAdapter);
        holder.spinner.setSelection(mSelectedFilter);
        holder.spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mSelectedFilter == position) {
                    return;
                }
                mSelectedFilter = position;
                mSpinnerAdapter.setSelectedPosition(position);
                mListener.onFilterSelected(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private static CharSequence buildSubtitle(View view, AppEntry entry) {
        final int profile = entry.overrideProfile != -1
                ? entry.overrideProfile
                : entry.selectedProfile;
        if (profile == -1) {
            return "";
        }
        final int nameRes = ThermalProfiles.nameRes(ThermalProfiles.canon(profile));
        return nameRes != 0 ? view.getContext().getString(nameRes)
                : String.valueOf(profile);
    }

    static final class FilterViewHolder extends RecyclerView.ViewHolder {
        final Spinner spinner;

        FilterViewHolder(View itemView) {
            super(itemView);
            spinner = itemView.findViewById(R.id.spinner);
        }
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView subtitle;

        ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            label = itemView.findViewById(R.id.app_label);
            subtitle = itemView.findViewById(R.id.app_subtitle);
        }
    }
}
