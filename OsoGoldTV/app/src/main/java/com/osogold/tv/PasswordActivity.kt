package com.osogold.tv

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView

/**
 * Royal password screen. It is shown only until the correct password has been entered once
 * (the state is saved in SharedPreferences). When the password is changed in the settings the
 * screen is shown again at the next start.
 */
class PasswordActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etPassword: EditText
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password)
        prefs = Prefs(this)

        etPassword = findViewById(R.id.etPassword)
        tvError = findViewById(R.id.tvError)
        val btnEnter = findViewById<TextView>(R.id.btnEnter)

        btnEnter.setOnClickListener { submit() }
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun submit() {
        val input = etPassword.text.toString()
        if (prefs.checkPassword(input)) {
            prefs.loggedIn = true
            hideKeyboard()
            // right after the password: the welcome video
            startActivity(Intent(this, WelcomeVideoActivity::class.java))
            finish()
        } else {
            tvError.visibility = View.VISIBLE
            etPassword.setText("")
            ObjectAnimator.ofFloat(
                etPassword, "translationX",
                0f, -24f, 24f, -16f, 16f, -8f, 8f, 0f
            ).setDuration(420L).start()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etPassword.windowToken, 0)
    }
}
