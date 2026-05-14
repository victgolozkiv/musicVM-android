package com.musicplayer.ui.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.musicplayer.R
import com.musicplayer.ui.MainActivity
import com.musicplayer.ui.adapter.QueueAdapter

class QueueBottomSheetFragment : BottomSheetDialogFragment() {
    private var mediaController: MediaController? = null
    private var adapter: QueueAdapter? = null
    private var recyclerView: RecyclerView? = null
    
    private val queueListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            updateQueue()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateQueue()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottom_sheet_queue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val activity = requireActivity() as MainActivity
        mediaController = activity.musicMediaController

        recyclerView = view.findViewById(R.id.rvQueue)
        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        
        adapter = QueueAdapter(object : QueueAdapter.OnQueueActionListener {
            override fun onItemClick(position: Int) {
                mediaController?.seekToDefaultPosition(position)
            }

            override fun onItemRemove(position: Int) {
                mediaController?.let {
                    it.removeMediaItem(position)
                    updateQueue()
                }
            }
        })
        
        recyclerView?.adapter = adapter
        
        setupItemTouchHelper()
        
        if (mediaController == null) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isAdded) {
                    mediaController = activity.musicMediaController
                    mediaController?.let {
                        it.addListener(queueListener)
                        updateQueue()
                    }
                }
            }, 500)
        } else {
            mediaController?.addListener(queueListener)
            updateQueue()
        }
    }

    override fun onDestroyView() {
        mediaController?.removeListener(queueListener)
        super.onDestroyView()
    }

    private fun updateQueue() {
        val controller = mediaController ?: return
        
        val items = mutableListOf<MediaItem>()
        for (i in 0 until controller.mediaItemCount) {
            items.add(controller.getMediaItemAt(i))
        }
        adapter?.updateQueue(items)
        
        recyclerView?.scrollToPosition(controller.currentMediaItemIndex)
    }

    private fun setupItemTouchHelper() {
        val simpleCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.absoluteAdapterPosition
                val to = target.absoluteAdapterPosition
                
                mediaController?.let {
                    it.moveMediaItem(from, to)
                    updateQueue()
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.absoluteAdapterPosition
                mediaController?.let {
                    it.removeMediaItem(position)
                    updateQueue()
                }
            }
        }
        
        ItemTouchHelper(simpleCallback).attachToRecyclerView(recyclerView)
    }
}
