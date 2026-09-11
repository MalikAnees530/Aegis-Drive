package com.malik.aegisdrive

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class SessionHistoryAdapter(private val sessionList: List<com.malik.aegisdrive.model.DriveSession>) : 
    RecyclerView.Adapter<SessionHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rootCard: MaterialCardView = view.findViewById(R.id.rootCard)
        val tvSessionTitle: TextView = view.findViewById(R.id.tvSessionTitle)
        val tvSessionDate: TextView = view.findViewById(R.id.tvSessionDate)
        val tvSessionScore: TextView = view.findViewById(R.id.tvSessionScore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessionList[position]
        
        // 🚀 Dynamic Numerical Naming (Assume sorted newest to oldest)
        val sessionNumber = sessionList.size - position 
        holder.tvSessionTitle.text = "Session $sessionNumber"
        
        // Use a formatted string from Timestamp
        val date = session.startTime?.toDate() ?: session.timestamp?.toDate() ?: java.util.Date()
        val dateString = java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault()).format(date)
        
        holder.tvSessionDate.text = dateString
        holder.tvSessionScore.text = "${session.score}%"

        // 🚀 THEME INJECTION
        val prefs = holder.itemView.context.getSharedPreferences("AegisSettings", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", true)

        if (isDarkMode) {
            holder.rootCard.setCardBackgroundColor(Color.parseColor("#1E1E1E"))
            holder.rootCard.setStrokeColor(Color.parseColor("#3C4043"))
            holder.tvSessionTitle.setTextColor(Color.parseColor("#FFFFFF"))
            holder.tvSessionDate.setTextColor(Color.parseColor("#BDBDBD"))
            holder.tvSessionScore.setTextColor(Color.parseColor("#38BDF8"))
        } else {
            holder.rootCard.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            holder.rootCard.setStrokeColor(Color.parseColor("#DADCE0"))
            holder.tvSessionTitle.setTextColor(Color.parseColor("#1F1F1F"))
            holder.tvSessionDate.setTextColor(Color.parseColor("#5F6368"))
            holder.tvSessionScore.setTextColor(Color.parseColor("#1F1F1F"))
        }

        holder.rootCard.setOnClickListener {
            val activity = it.context as? AppCompatActivity
            activity?.supportFragmentManager?.let { fm ->
                SessionDetailBottomSheet(session.id).show(fm, "SessionDetail")
            }
        }
    }

    override fun getItemCount() = sessionList.size
}
