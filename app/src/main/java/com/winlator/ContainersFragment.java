package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.api.ManagedContainerInfo;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.StorageInfoDialog;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.StringUtils;
import com.winlator.xenvironment.RootFS;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class ContainersFragment extends Fragment {
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private PreloaderDialog preloaderDialog;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        loadContainersList();
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.containers);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout)inflater.inflate(R.layout.containers_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        Context context = recyclerView.getContext();
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        DividerItemDecoration itemDecoration = new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL);
        itemDecoration.setDrawable(ContextCompat.getDrawable(context, R.drawable.list_item_divider));
        recyclerView.addItemDecoration(itemDecoration);
        return frameLayout;
    }

    private void loadContainersList() {
        ArrayList<Container> containers = new ArrayList<>(manager.getContainers());
        Context context = getContext();
        if (context == null) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            Map<Integer, ManagedContainerInfo> managedInfo = new HashMap<>();
            for (Container container : containers) {
                try {
                    managedInfo.put(
                            container.id,
                            ManagedContainerInfo.inspect(context, container.id)
                    );
                }
                catch (JSONException | IOException e) {
                    managedInfo.clear();
                    break;
                }
            }
            Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                recyclerView.setAdapter(new ContainersAdapter(containers, managedInfo));
                emptyTextView.setVisibility(
                        containers.isEmpty() ? View.VISIBLE : View.GONE
                );
            });
        });
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.containers_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.menu_item_add) {
            if (!RootFS.find(getContext()).isValid()) return false;
            FragmentManager fragmentManager = getParentFragmentManager();
            fragmentManager.beginTransaction()
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                .commit();
            return true;
        }
        else return super.onOptionsItemSelected(menuItem);
    }

    private class ContainersAdapter extends RecyclerView.Adapter<ContainersAdapter.ViewHolder> {
        private final List<Container> data;
        private final Map<Integer, ManagedContainerInfo> managedInfo;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton;
            private final ImageView menuButton;
            private final ImageView imageView;
            private final TextView title;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.runButton = view.findViewById(R.id.BTRun);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public ContainersAdapter(
                List<Container> data,
                Map<Integer, ManagedContainerInfo> managedInfo
        ) {
            this.data = data;
            this.managedInfo = managedInfo;
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.container_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final Container item = data.get(position);
            holder.imageView.setImageResource(R.drawable.icon_container);
            holder.title.setText(containerTitle(item));
            holder.runButton.setOnClickListener((view) -> runContainer(item));
            holder.menuButton.setOnClickListener((view) -> showListItemMenu(view, item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, Container container) {
            MainActivity activity = (MainActivity)getActivity();
            PopupMenu listItemMenu = new PopupMenu(activity, anchorView);
            listItemMenu.inflate(R.menu.container_popup_menu);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                switch (menuItem.getItemId()) {
                    case R.id.menu_item_file_manager:
                        activity.showFragment(new ContainerFileManagerFragment(container.id));
                        break;
                    case R.id.menu_item_edit:
                        activity.showFragment(new ContainerDetailFragment(container.id));
                        break;
                    case R.id.menu_item_duplicate:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_duplicate_this_container, () -> {
                            preloaderDialog.show(R.string.duplicating_container);
                            manager.duplicateContainerAsync(container, (result) -> {
                                if (!isAdded() || activity.isFinishing() ||
                                        activity.isDestroyed()) {
                                    return;
                                }
                                preloaderDialog.close();
                                if (result.container == null) {
                                    String text;
                                    if ("insufficient_storage".equals(result.error) &&
                                            result.requiredBytes >= 0 &&
                                            result.availableBytes >= 0) {
                                        text = getString(
                                                R.string.container_duplicate_insufficient_storage,
                                                StringUtils.formatBytes(result.requiredBytes),
                                                StringUtils.formatBytes(result.availableBytes)
                                        );
                                    }
                                    else if ("size_unavailable".equals(result.error)) {
                                        text = getString(
                                                R.string.container_duplicate_size_unavailable
                                        );
                                    }
                                    else {
                                        text = getString(
                                                R.string.container_duplicate_failed
                                        );
                                    }
                                    Toast.makeText(
                                            activity,
                                            text,
                                            Toast.LENGTH_LONG
                                    ).show();
                                }
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.menu_item_remove:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_container, () -> {
                            preloaderDialog.show(R.string.removing_container);
                            Executors.newSingleThreadExecutor().execute(() -> {
                                try {
                                    ManagedContainerInfo.RemovalResult result =
                                            ManagedContainerInfo.removeIfUnreferenced(
                                                    requireContext(),
                                                    manager,
                                                    container
                                            );
                                    activity.runOnUiThread(() -> {
                                        preloaderDialog.close();
                                        if (result.blocked) {
                                            int message = result.info.shared
                                                    ? R.string.agm_shared_container_delete_blocked
                                                    : R.string.agm_container_delete_blocked;
                                            String text = result.info.shared
                                                    ? getString(message)
                                                    : getString(
                                                            message,
                                                            result.info.referenceCount
                                                    );
                                            Toast.makeText(
                                                    activity,
                                                    text,
                                                    Toast.LENGTH_LONG
                                            ).show();
                                        }
                                        loadContainersList();
                                    });
                                }
                                catch (JSONException | IOException e) {
                                    activity.runOnUiThread(() -> {
                                        preloaderDialog.close();
                                        Toast.makeText(
                                                activity,
                                                R.string.agm_container_status_failed,
                                                Toast.LENGTH_LONG
                                        ).show();
                                    });
                                }
                            });
                        });
                        break;
                    case R.id.menu_item_info:
                        (new StorageInfoDialog(activity, container)).show();
                        break;
                }

                return true;
            });
            listItemMenu.show();
        }

        private CharSequence containerTitle(Container container) {
            ManagedContainerInfo info = managedInfo.get(container.id);
            if (info == null || (info.referenceCount == 0 && !info.shared)) {
                return container.getName();
            }
            String games = TextUtils.join(", ", info.gameTitles);
            int summary = info.shared
                    ? R.string.agm_shared_container_summary
                    : R.string.agm_isolated_container_summary;
            String detail = info.shared
                    ? getString(summary, info.referenceCount, games)
                    : getString(summary, games);
            return container.getName()+"\n"+detail;
        }

        private void runContainer(Container container) {
            Activity activity = getActivity();
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            activity.startActivity(intent);
        }
    }
}
