package flhan.de.financemanager.common

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import flhan.de.financemanager.base.RequestResult
import flhan.de.financemanager.common.data.Expense
import flhan.de.financemanager.common.data.Household
import flhan.de.financemanager.common.data.User
import flhan.de.financemanager.common.events.Create
import flhan.de.financemanager.common.events.Delete
import flhan.de.financemanager.common.events.RepositoryEvent
import flhan.de.financemanager.common.events.Update
import flhan.de.financemanager.ui.login.createjoinhousehold.NoSuchHouseholdThrowable
import flhan.de.financemanager.ui.main.expenses.createedit.NoExpenseFoundThrowable
import io.reactivex.*
import javax.inject.Inject

/**
 * Created by Florian on 14.09.2017.
 */
interface RemoteDataStore {
    fun createHousehold(household: Household): Single<RequestResult<Household>>
    fun joinHousehold(household: Household): Single<RequestResult<Household>>
    fun joinHouseholdByMail(email: String): Single<RequestResult<Household>>
    fun getCurrentUser(): User
    fun loadExpenses(): Observable<RepositoryEvent<Expense>>
    fun findExpenseBy(id: String): Observable<RequestResult<Expense>>
    fun loadUsers(): Observable<MutableList<User>>
    fun saveExpense(expense: Expense): Completable
}

class FirebaseClient @Inject constructor(private val userSettings: UserSettings) : RemoteDataStore {

    private val firebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    private val rootReference by lazy { firebaseDatabase.getReference(HOUSEHOLD) }
    private val usersObservable by lazy {
        createUserObservable()
                .replay(1)
                .refCount()
    }

    init {
        firebaseDatabase.setPersistenceEnabled(true)
    }

    override fun findExpenseBy(id: String): Observable<RequestResult<Expense>> {
        return usersObservable.flatMap { users ->
            Observable.create { emitter: ObservableEmitter<RequestResult<Expense>> ->
                val ref = rootReference.child("${userSettings.getHouseholdId()}/$EXPENSES/$id")
                val listener = object : ValueEventListener {
                    override fun onCancelled(databaseError: DatabaseError?) {
                        emitter.onNext(RequestResult(null, NoExpenseFoundThrowable("Could not find Expense for id $id")))
                    }

                    override fun onDataChange(dataSnapshot: DataSnapshot?) {
                        dataSnapshot?.let {
                            val expense = dataSnapshot.getValue(Expense::class.java)
                            if (expense != null) {
                                expense.user = users.firstOrNull { expense.creator == it.id }
                                emitter.onNext(RequestResult(expense))
                            } else {
                                emitter.onNext(RequestResult(null, NoExpenseFoundThrowable("Could not find Expense for id $id")))
                            }
                        }
                        emitter.onComplete()
                    }
                }
                ref.addListenerForSingleValueEvent(listener)
                emitter.setCancellable { ref.removeEventListener(listener) }
            }
        }.onErrorReturn { RequestResult(null, NoExpenseFoundThrowable("Could not find Expense for id $id")) }
    }

    override fun saveExpense(expense: Expense): Completable {
        return Completable.fromAction {
            expense.creator = getCurrentUser().id
            val ref = rootReference.child("${userSettings.getHouseholdId()}/$EXPENSES/").push()
            expense.id = ref.key
            ref.setValue(expense)
        }
    }

    //TODO: Check name existency before creation
    override fun createHousehold(household: Household): Single<RequestResult<Household>> {
        return Single.create { emitter: SingleEmitter<RequestResult<Household>> ->
            household.creator = getCurrentUser().email
            val key = rootReference.push().key
            household.id = key
            rootReference.child(key).setValue(household)
            emitter.onSuccess(RequestResult(household))
        }.onErrorReturn { RequestResult(null, it) }
    }

    override fun joinHousehold(household: Household): Single<RequestResult<Household>> {
        return Single.create { emitter: SingleEmitter<RequestResult<Household>> ->
            performJoin(household)
            emitter.onSuccess(RequestResult(household))
        }.onErrorReturn { RequestResult(null, it) }
    }

    private fun performJoin(household: Household) {
        val user = getCurrentUser()

        val householdUserRef = rootReference.child("${household.id}/$USERS/")
        val userId = householdUserRef.push().key
        user.id = userId
        household.users.put(user.id, user)
        householdUserRef.child(userId).setValue(user)
        userSettings.setUserId(userId)
        userSettings.setHouseholdId(household.id)
    }

    override fun getCurrentUser(): User {
        val user = User()
        val currentAuthorizedUser = FirebaseAuth.getInstance().currentUser
        currentAuthorizedUser?.let {
            user.name = currentAuthorizedUser.displayName ?: ""
            user.email = currentAuthorizedUser.email ?: ""
        }
        val userId = userSettings.getUserId()
        if (!userId.isEmpty()) {
            user.id = userId
        }
        return user
    }

    override fun joinHouseholdByMail(email: String): Single<RequestResult<Household>> {
        return Single.create({ emitter: SingleEmitter<RequestResult<Household>> ->
            rootReference.orderByChild(CREATOR)
                    .equalTo(email)
                    .addListenerForSingleValueEvent(object : ValueEventListener {

                        override fun onCancelled(databaseError: DatabaseError?) {
                            emitter.onSuccess(RequestResult(null, databaseError?.toException() ?: Exception("Could not fetch household for email $email")))
                        }

                        override fun onDataChange(dataSnapshot: DataSnapshot?) {
                            if (dataSnapshot?.childrenCount?.toInt() != 0) {
                                val first = dataSnapshot?.children?.first()
                                val household = first?.getValue(Household::class.java)
                                performJoin(household!!)
                                emitter.onSuccess(RequestResult(household))
                            } else {
                                emitter.onSuccess(RequestResult(null, NoSuchHouseholdThrowable()))
                            }
                        }
                    })
        }).onErrorReturn { RequestResult(null, it) }
    }

    override fun loadExpenses(): Observable<RepositoryEvent<Expense>> {
        return usersObservable.flatMap { users ->
            return@flatMap createExpenseObservable(users)
        }
    }

    override fun loadUsers(): Observable<MutableList<User>> {
        return usersObservable
    }

    private fun createUserObservable(): Observable<MutableList<User>> {
        return Observable.create { emitter: ObservableEmitter<MutableList<User>> ->
            val users = mutableListOf<User>()

            val valueListener = object : ValueEventListener {
                override fun onCancelled(databaseError: DatabaseError?) {
                    emitter.onError(databaseError?.toException() ?: Throwable("Listener.OnCancelled"))
                }

                override fun onDataChange(dataSnapshot: DataSnapshot?) {
                    dataSnapshot?.let {
                        for (snapshot in dataSnapshot.children) {
                            val user = snapshot.getValue(User::class.java)
                            user?.let {
                                user.id = snapshot.key
                                users.add(user)
                            }
                        }
                    }
                    emitter.onNext(users)
                }
            }

            val ref = rootReference.child("${userSettings.getHouseholdId()}/$USERS")
            ref.keepSynced(true)
            ref.addListenerForSingleValueEvent(valueListener)
            emitter.setCancellable {
                ref.removeEventListener(valueListener)
            }
        }
    }

    private fun createExpenseObservable(users: List<User>): Observable<RepositoryEvent<Expense>> {
        return Observable.create { emitter ->
            val listener = object : ChildEventListener {
                override fun onCancelled(databaseError: DatabaseError?) {
                    emitter.onError(databaseError?.toException()?.cause ?: Throwable("Listener.OnCancelled"))
                }

                override fun onChildMoved(dataSnapshot: DataSnapshot?, p1: String?) {
                }

                override fun onChildChanged(dataSnapshot: DataSnapshot?, p1: String?) {
                    dataSnapshot?.let {
                        val expense = dataSnapshot.getValue(Expense::class.java)
                        expense?.apply {
                            id = dataSnapshot.key
                            user = users.firstOrNull { creator == it.id }
                        }
                        val event = Update(expense!!)
                        emitter.onNext(event)
                    }
                }

                override fun onChildAdded(dataSnapshot: DataSnapshot?, p1: String?) {
                    dataSnapshot?.let {
                        val expense = dataSnapshot.getValue(Expense::class.java)
                        expense?.apply {
                            id = dataSnapshot.key
                            user = users.firstOrNull { creator == it.id }
                        }
                        val event = Create(expense!!)
                        emitter.onNext(event)
                    }
                }

                override fun onChildRemoved(dataSnapshot: DataSnapshot?) {
                    dataSnapshot?.let {
                        val key = dataSnapshot.key
                        val event = Delete<Expense>(key)
                        emitter.onNext(event)
                    }
                }
            }
            rootReference.child("${userSettings.getHouseholdId()}/$EXPENSES").addChildEventListener(listener)
            emitter.setCancellable { rootReference.removeEventListener(listener) }
        }
    }

    companion object {
        const val EXPENSES = "expenses"
        const val USERS = "users"
        const val HOUSEHOLD = "households"
        const val CREATOR = "creator"
    }
}