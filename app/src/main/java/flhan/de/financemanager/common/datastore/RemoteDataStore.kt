package flhan.de.financemanager.common.datastore

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import flhan.de.financemanager.base.RequestResult
import flhan.de.financemanager.common.data.Billing
import flhan.de.financemanager.common.data.Expense
import flhan.de.financemanager.common.data.Household
import flhan.de.financemanager.common.data.User
import flhan.de.financemanager.ui.login.createjoinhousehold.join.InvalidSecretThrowable
import flhan.de.financemanager.ui.login.createjoinhousehold.join.NoSuchHouseholdThrowable
import flhan.de.financemanager.ui.main.expenses.createedit.NoExpenseFoundThrowable
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import io.reactivex.Single
import io.reactivex.SingleEmitter
import io.reactivex.rxkotlin.withLatestFrom
import javax.inject.Inject


/**
 * Created by Florian on 14.09.2017.
 */
interface RemoteDataStore {
    fun createHousehold(household: Household): Single<RequestResult<Household>>
    fun joinHousehold(household: Household): Single<RequestResult<Household>>
    fun joinHouseholdByMail(email: String, secret: String): Single<RequestResult<Household>>
    fun getCurrentUser(): User
    fun loadExpenses(): Observable<MutableList<Expense>>
    fun findExpenseBy(id: String): Observable<RequestResult<Expense>>
    fun loadUsers(): Observable<MutableList<User>>
    fun saveExpense(expense: Expense): Observable<Unit>
    fun deleteExpense(id: String): Single<Boolean>
    fun saveBilling(billing: Billing): Single<RequestResult<Billing>>
    fun deleteExpenses(): Single<Boolean>
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
                val ref = rootReference.child("${userSettings.getHouseholdId()}/${EXPENSES}/$id")
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

    override fun saveExpense(expense: Expense): Observable<Unit> {
        return if (expense.id.isBlank()) {
            createExpense(expense)
        } else {
            updateExpense(expense)
        }
    }

    override fun deleteExpenses(): Single<Boolean> {
        return Single.create { emitter ->
            val ref = rootReference.child("${userSettings.getHouseholdId()}/$EXPENSES/")
            ref.removeValue { error, ref ->
                if (error == null) {
                    emitter.onSuccess(true)
                } else {
                    emitter.onSuccess(false)
                }
            }

        }
    }

    override fun createHousehold(household: Household): Single<RequestResult<Household>> {
        return Single.create { emitter: SingleEmitter<RequestResult<Household>> ->
            val key = rootReference.push().key
            household.apply {
                creator = getCurrentUser().email
                id = key
            }
            rootReference.child(key).setValue(household)
            emitter.onSuccess(RequestResult(household))
        }.onErrorReturn { RequestResult(null, it) }
    }

    override fun joinHousehold(household: Household): Single<RequestResult<Household>> {
        return Single.create { emitter: SingleEmitter<RequestResult<Household>> ->
            performJoin(household, household.secret, {
                emitter.onSuccess(RequestResult(household))
            }, { throwable ->
                emitter.onSuccess(RequestResult(null, throwable))
            })
        }.onErrorReturn { RequestResult(null, it) }
    }

    //TODO: Throw better exception
    override fun getCurrentUser(): User {
        val user = User()
        val userId = userSettings.getUserId()
        val currentAuthorizedUser = FirebaseAuth.getInstance().currentUser?.let { it }
                ?: throw Throwable()
        user.apply {
            name = currentAuthorizedUser.displayName ?: ""
            email = currentAuthorizedUser.email ?: ""
            id = userId
        }
        return user
    }

    override fun joinHouseholdByMail(email: String, secret: String): Single<RequestResult<Household>> {
        return Single.create({ emitter: SingleEmitter<RequestResult<Household>> ->
            rootReference.orderByChild(CREATOR)
                    .equalTo(email)
                    .addListenerForSingleValueEvent(object : ValueEventListener {

                        override fun onCancelled(databaseError: DatabaseError?) {
                            emitter.onSuccess(RequestResult(null, databaseError?.toException()
                                    ?: Exception("Could not fetch household for email $email")))
                        }

                        override fun onDataChange(dataSnapshot: DataSnapshot?) {
                            if (dataSnapshot?.childrenCount?.toInt() != 0) {
                                val first = dataSnapshot?.children?.first()
                                val household = first?.getValue(Household::class.java)
                                performJoin(household!!, secret, {
                                    emitter.onSuccess(RequestResult(household))
                                }, { throwable ->
                                    emitter.onSuccess(RequestResult(null, throwable))
                                })
                            } else {
                                emitter.onSuccess(RequestResult(null, NoSuchHouseholdThrowable()))
                            }
                        }
                    })
        }).onErrorReturn { RequestResult(null, it) }
    }

    override fun loadExpenses(): Observable<MutableList<Expense>> {
        return createExpenseObservable()
                .withLatestFrom(usersObservable, { expenses: MutableList<Expense>, userList: MutableList<User>? ->
                    for (expense in expenses.filter { it.user == null }) {
                        val user = userList?.firstOrNull { expense.creator == it.id }
                        expense.user = user
                    }
                    return@withLatestFrom expenses
                })
    }

    override fun loadUsers(): Observable<MutableList<User>> {
        return usersObservable
    }

    override fun deleteExpense(id: String): Single<Boolean> {
        return Single.fromCallable {
            val ref = rootReference.child("${userSettings.getHouseholdId()}/$EXPENSES/$id")
            ref.removeValue()
            return@fromCallable true
        }
    }

    override fun saveBilling(billing: Billing): Single<RequestResult<Billing>> {
        return Single.create { emitter ->
            val billingRef = rootReference.child("${userSettings.getHouseholdId()}/$BILLING/").push()
            billing.id = billingRef.key
            billingRef.setValue(billing, { error, ref ->
                if (error != null) {
                    emitter.onSuccess(RequestResult(null, error.toException()))
                } else {
                    emitter.onSuccess(RequestResult(billing))
                }
            })
        }
    }

    private fun createUserObservable(): Observable<MutableList<User>> {
        return Observable.create { emitter: ObservableEmitter<MutableList<User>> ->
            val users = mutableListOf<User>()
            var isInitialLoadingDone = false

            val valueListener = object : ValueEventListener {
                override fun onCancelled(databaseError: DatabaseError?) {
                    emitter.onError(databaseError?.toException()
                            ?: Throwable("Listener.OnCancelled"))
                }

                override fun onDataChange(dataSnapshot: DataSnapshot?) {
                    isInitialLoadingDone = true
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
            val childListener = object : ChildEventListener {
                override fun onCancelled(p0: DatabaseError?) {
                }

                override fun onChildMoved(p0: DataSnapshot?, p1: String?) {
                }

                override fun onChildChanged(p0: DataSnapshot?, p1: String?) {
                }

                override fun onChildAdded(dataSnapshot: DataSnapshot?, p1: String?) {
                    if (isInitialLoadingDone) {
                        dataSnapshot?.let {
                            val user = dataSnapshot.getValue(User::class.java)
                            user?.let {
                                user.id = dataSnapshot.key
                                users.add(user)
                            }
                        }
                        emitter.onNext(users)
                    }
                }

                override fun onChildRemoved(p0: DataSnapshot?) {
                }
            }
            val ref = rootReference.child("${userSettings.getHouseholdId()}/$USERS")
            ref.keepSynced(true)
            ref.addChildEventListener(childListener)
            ref.addListenerForSingleValueEvent(valueListener)
            emitter.setCancellable {
                ref.removeEventListener(valueListener)
                ref.removeEventListener(childListener)
            }
        }
    }

    private fun createExpenseObservable(): Observable<MutableList<Expense>> {
        return Observable.create { emitter ->
            val expenses = mutableListOf<Expense>()
            var isInitialLoadingDone = false

            val valueListener = object : ValueEventListener {
                override fun onCancelled(databaseError: DatabaseError?) {
                    emitter.onError(databaseError?.toException()
                            ?: Throwable("Listener.OnCancelled"))
                }

                override fun onDataChange(dataSnapshot: DataSnapshot?) {
                    dataSnapshot?.apply {
                        for (snapshot in children) {
                            val expense = snapshot.getValue(Expense::class.java)
                            expense?.let {
                                expense.id = snapshot.key
                                expenses.add(expense)
                            }
                        }
                        isInitialLoadingDone = true
                        emitter.onNext(expenses)
                    }
                }
            }

            val childEventListener = object : ChildEventListener {
                override fun onCancelled(databaseError: DatabaseError?) {
                    emitter.onError(databaseError?.toException()?.cause
                            ?: Throwable("Listener.OnCancelled"))
                }

                override fun onChildMoved(dataSnapshot: DataSnapshot?, p1: String?) {
                }

                override fun onChildChanged(dataSnapshot: DataSnapshot?, p1: String?) {
                    if (isInitialLoadingDone) {
                        dataSnapshot?.let {
                            val expense = dataSnapshot.getValue(Expense::class.java)
                            expense?.apply {
                                id = dataSnapshot.key
                                val index = expenses.indexOfFirst { id == it.id }
                                expenses[index] = this
                            }
                            emitter.onNext(expenses)
                        }
                    }
                }

                override fun onChildAdded(dataSnapshot: DataSnapshot?, p1: String?) {
                    if (isInitialLoadingDone) {
                        dataSnapshot?.apply {
                            val expense = getValue(Expense::class.java)
                            expense?.apply {
                                id = key
                                expenses.add(this)
                            }
                            emitter.onNext(expenses)
                        }
                    }
                }

                override fun onChildRemoved(dataSnapshot: DataSnapshot?) {
                    dataSnapshot?.apply {
                        val key = key
                        val index = expenses.indexOfFirst { key == it.id }
                        expenses.removeAt(index)
                        emitter.onNext(expenses)
                    }
                }
            }

            val ref = rootReference.child("${userSettings.getHouseholdId()}/$EXPENSES")
            ref.apply {
                keepSynced(true)
                addChildEventListener(childEventListener)
                addListenerForSingleValueEvent(valueListener)
            }
            emitter.setCancellable { rootReference.removeEventListener(childEventListener) }
        }
    }

    private fun performJoin(household: Household, secret: String, success: () -> Unit, error: (Throwable) -> Unit) {
        val user = getCurrentUser()
        val householdRef = rootReference.child(household.id)

        householdRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onCancelled(p0: DatabaseError?) {
                error(Throwable("Cancelled"))
            }

            override fun onDataChange(dataSnapshot: DataSnapshot?) {
                val householdToJoin = dataSnapshot?.getValue(Household::class.java)
                if (householdToJoin == null) {
                    error(NoSuchHouseholdThrowable())
                    return
                }

                if (householdToJoin.secret == secret) {
                    val householdUserRef = householdRef.child(USERS)
                    val userId = householdUserRef.push().key
                    user.id = userId
                    household.users.put(user.id, user)
                    householdUserRef.child(userId).setValue(user)
                    userSettings.apply {
                        setUserId(userId)
                        setHouseholdId(household.id)
                    }
                    success()
                } else {
                    error(InvalidSecretThrowable())
                }
            }
        })
    }

    private fun updateExpense(expense: Expense): Observable<Unit> {
        return Observable.create { emitter ->
            val expenseRef = rootReference.child("${userSettings.getHouseholdId()}/$EXPENSES/${expense.id}")
            val updateMap = mutableMapOf<String, Any>()
            updateMap[Expense.AMOUNT] = expense.amount!!
            updateMap[Expense.CAUSE] = expense.cause
            updateMap[Expense.CREATOR] = expense.creator
            updateMap[Expense.CREATOR_NAME] = expense.creatorName

            expenseRef.updateChildren(updateMap) { databaseError, _ ->
                if (databaseError == null) {
                    emitter.onNext(Unit)
                    emitter.onComplete()
                } else {
                    emitter.onError(databaseError.toException())
                }
            }
        }
    }

    private fun createExpense(expense: Expense): Observable<Unit> {
        return Observable.create { emitter ->
            val ref = rootReference.child("${userSettings.getHouseholdId()}/$EXPENSES/").push()
            expense.id = ref.key
            ref.setValue(expense)
            emitter.onNext(Unit)
            emitter.onComplete()
        }
    }

    companion object {
        const val EXPENSES = "expenses"
        const val USERS = "users"
        const val HOUSEHOLD = "households"
        const val CREATOR = "creator"
        const val BILLING = "billing"
    }
}