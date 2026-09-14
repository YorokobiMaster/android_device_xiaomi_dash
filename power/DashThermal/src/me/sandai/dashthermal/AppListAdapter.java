// Copyright (C) 2026 GitHub @YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Adapter for the per-app rows on the main page. */
final class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    interface Listener {
        void onAppClick(AppEntry entry);

        /** Called from onBindViewHolder for icons that are not cached yet. */
        void onIconNeeded(AppEntry entry);

        /** Called from onBindViewHolder for rows whose stock group is unknown. */
        void onPolicyNeeded(AppEntry entry);
    }

    private final Listener mListener;
    private final List<AppEntry> mItems = new ArrayList<>();

    AppListAdapter(Listener listener) {
        mListener = listener;
    }

    void setItems(List<AppEntry> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    int indexOf(String packageName) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).packageName.equals(packageName)) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final AppEntry entry = mItems.get(position);
        holder.label.setText(entry.label);
        holder.subtitle.setText(buildSubtitle(holder.itemView, entry));

        final Drawable icon = IconCache.get(holder.itemView.getContext(), entry.packageName);
        if (icon != null) {
            holder.icon.setImageDrawable(icon);
        } else {
            holder.icon.setImageDrawable(IconCache.getDefault(holder.itemView.getContext()));
            mListener.onIconNeeded(entry);
        }

        if (entry.overrideProfile == -1 && entry.stockGroup == null && !entry.policyRequested) {
            entry.policyRequested = true;
            mListener.onPolicyNeeded(entry);
        }

        holder.itemView.setOnClickListener(v -> mListener.onAppClick(entry));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    private static CharSequence buildSubtitle(View view, AppEntry entry) {
        if (entry.overrideProfile != -1) {
            final int nameRes = ThermalProfiles.nameRes(entry.overrideProfile);
            return nameRes != 0 ? view.getContext().getString(nameRes)
                    : String.valueOf(entry.overrideProfile);
        }
        if (entry.stockGroup != null && !entry.stockGroup.isEmpty()) {
            return view.getContext().getString(R.string.list_subtitle_auto_category,
                    entry.stockGroup);
        }
        return view.getContext().getString(R.string.profile_auto);
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
