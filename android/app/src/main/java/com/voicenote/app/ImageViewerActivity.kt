package com.voicenote.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.voicenote.app.databinding.ActivityImageViewerBinding
import java.io.File

/**
 * Fullscreen, swipeable, pinch/double-tap zoomable image viewer. Opened from
 * a note row's thumbnail or a thread reply's image strip. Takes a list of
 * local file paths + a start index — the files are already downscaled by
 * [ImageStore], so they load directly.
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val paths = intent.getStringArrayListExtra(EXTRA_PATHS)?.filter { File(it).exists() }
            ?: emptyList()
        if (paths.isEmpty()) {
            finish()
            return
        }
        val start = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, paths.size - 1)

        binding.pager.adapter = PagerAdapter(paths)
        binding.pager.setCurrentItem(start, false)

        if (paths.size > 1) {
            binding.tvIndicator.visibility = View.VISIBLE
            updateIndicator(start, paths.size)
            binding.pager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) = updateIndicator(position, paths.size)
                }
            )
        }

        binding.btnClose.setOnClickListener { finish() }
    }

    private fun updateIndicator(position: Int, total: Int) {
        binding.tvIndicator.text = "${position + 1} / $total"
    }

    private class PagerAdapter(private val paths: List<String>) :
        RecyclerView.Adapter<PagerAdapter.VH>() {

        class VH(val image: ZoomableImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ZoomableImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.image.setImageURI(Uri.fromFile(File(paths[position])))
        }

        override fun getItemCount(): Int = paths.size
    }

    companion object {
        private const val EXTRA_PATHS = "paths"
        private const val EXTRA_START_INDEX = "start"

        fun open(context: Context, paths: List<String>, startIndex: Int = 0) {
            if (paths.isEmpty()) return
            context.startActivity(
                Intent(context, ImageViewerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
                    .putExtra(EXTRA_START_INDEX, startIndex)
            )
        }
    }
}
