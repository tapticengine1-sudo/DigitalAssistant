package com.example.assistant

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AttachmentBottomSheet(private val onChoice: (Choice) -> Unit) : BottomSheetDialogFragment() {

    enum class Choice { CAMERA, GALLERY, FILE }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_attachment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnCamera).setOnClickListener {
            onChoice(Choice.CAMERA)
            dismiss()
        }

        view.findViewById<View>(R.id.btnGallery).setOnClickListener {
            onChoice(Choice.GALLERY)
            dismiss()
        }

        view.findViewById<View>(R.id.btnFile).setOnClickListener {
            onChoice(Choice.FILE)
            dismiss()
        }
    }
}