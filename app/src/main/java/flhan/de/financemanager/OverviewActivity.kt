package flhan.de.financemanager

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import flhan.de.financemanager.data.Household


class OverviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overview)
        testStore()
    }

    private fun testStore() {
        val database = FirebaseDatabase.getInstance()
        val reference = database.getReference("root")

        val households: List<Household> = mutableListOf(
                Household("Household 1"),
                Household("Household 2")
        )

        households.forEach {
            val key = reference.child("households").push().key
            it.id = key
            reference.child("households").child(key).setValue(it)
        }
    }
}
