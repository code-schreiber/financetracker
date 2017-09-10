package flhan.de.financemanager.signin

import android.content.Intent
import android.os.Bundle
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.GoogleApiClient
import flhan.de.financemanager.R
import flhan.de.financemanager.base.BaseActivity
import flhan.de.financemanager.base.app
import flhan.de.financemanager.di.signin.LoginModule
import kotlinx.android.synthetic.main.activity_login.*
import javax.inject.Inject

//TODO: This should not be the start of the application. Instead, FirebaseAuth.AuthstateListener -> see if user is not null.
class LoginActivity : BaseActivity(), LoginContract.View, GoogleApiClient.OnConnectionFailedListener {
    @Inject
    lateinit var presenter: LoginContract.Presenter
    private val SIGN_IN_ID: Int = 12515

    private val component by lazy { app.appComponent.plus(LoginModule(this)) }

    private val mGoogleApiClient: GoogleApiClient by lazy {
        GoogleApiClient.Builder(this)
                .enableAutoManage(this, this)
                .addApi(Auth.GOOGLE_SIGN_IN_API, mGoogleSignInOptions)
                .build()
    }

    private val mGoogleSignInOptions: GoogleSignInOptions by lazy {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(getString(R.string.default_web_client_id))
                .build()
    }

    private val loginButton: SignInButton
        get() = this.login_with_google


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        component.inject(this)
        loginButton.setOnClickListener { startGoogleAuth() }
    }

    private fun startGoogleAuth() {
        val signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient)
        startActivityForResult(signInIntent, SIGN_IN_ID)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SIGN_IN_ID) {
            val result = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
            //TODO: Handle else case
            if (result.isSuccess) {
                val acct = result.signInAccount
                if (acct != null) {
                    val token = acct.idToken!!
                    presenter.startAuth(token)
                }
            }
        }
    }

    override fun onConnectionFailed(p0: ConnectionResult) {
        //TODO: Handle
    }
}
