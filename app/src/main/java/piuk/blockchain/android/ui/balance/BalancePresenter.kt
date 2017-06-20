package piuk.blockchain.android.ui.balance

import info.blockchain.wallet.payload.data.LegacyAddress
import io.reactivex.Observable
import piuk.blockchain.android.R
import piuk.blockchain.android.data.datamanagers.ContactsDataManager
import piuk.blockchain.android.data.datamanagers.PayloadDataManager
import piuk.blockchain.android.data.datamanagers.TransactionListDataManager
import piuk.blockchain.android.injection.Injector
import piuk.blockchain.android.ui.account.ConsolidatedAccount
import piuk.blockchain.android.ui.account.ItemAccount
import piuk.blockchain.android.ui.base.BasePresenter
import piuk.blockchain.android.ui.customviews.ToastCustom
import piuk.blockchain.android.util.ExchangeRateFactory
import piuk.blockchain.android.util.MonetaryUtil
import piuk.blockchain.android.util.PrefsUtil
import piuk.blockchain.android.util.StringUtils
import javax.inject.Inject

class BalancePresenter : BasePresenter<BalanceView>() {

    @Inject lateinit var exchangeRateFactory: ExchangeRateFactory
    @Inject lateinit var transactionListDataManager: TransactionListDataManager
    @Inject lateinit var contactsDataManager: ContactsDataManager
    @Inject lateinit var payloadDataManager: PayloadDataManager
    @Inject lateinit var stringUtils: StringUtils
    @Inject lateinit var prefsUtil: PrefsUtil

    private var activeAccountAndAddressList: MutableList<ItemAccount> = mutableListOf()
    private var chosenAccount: ItemAccount? = null

    init {
        Injector.getInstance().dataManagerComponent.inject(this)
    }

    override fun onViewReady() {
        view.setShowRefreshing(true)
        activeAccountAndAddressList = getAllDisplayableAccounts()
        chosenAccount = activeAccountAndAddressList[0]

        val contactsObservable = contactsDataManager.fetchContacts()
                .andThen(contactsDataManager.contactsWithUnreadPaymentRequests)

        val fctxObservable = contactsDataManager.facilitatedTransactions

        refreshTransactionList()

        /**
         * TODO: Ideally here we would concatenate:
         *
         * 0) Getting all the user's accounts + legacy addresses
         * 1) Updating the ticker price
         * 2) Updating the current balance
         * 2) Getting the transaction list
         * 3) Getting the Contacts list
         * 4) Get Facilitated Transactions
         *
         * All into one Observable and handling onNext in each component Observable.
         *
         */
    }

    fun onAccountChosen(position: Int) {
        chosenAccount = activeAccountAndAddressList[position]
        chosenAccount?.let { chosenAccount ->
            val fetchTransactionsObservable = getTransactionsListObservable(chosenAccount)
            val balanceCompletable = getBalanceObservable(chosenAccount)

            Observable.merge(balanceCompletable, fetchTransactionsObservable)
                    .subscribe(
                            { /* No-op */ },
                            { view.showToast(R.string.unexpected_error, ToastCustom.TYPE_ERROR) })
        }
    }

    fun onRefreshRequested() = refreshTransactionList()

    private fun refreshTransactionList() {
        chosenAccount?.let { chosenAccount ->
            Observable.merge(
                    getBalanceObservable(chosenAccount),
                    getTransactionsListObservable(chosenAccount),
                    getUpdateTickerObservable()
            ).doOnTerminate { view.setShowRefreshing(false) }
                    .subscribe(
                            { /* No-op */ },
                            {
                                // TODO: Update UI with failure state here, or perhaps in component observables?
                                throwable -> throwable.printStackTrace()
                                view.showToast(R.string.unexpected_error, ToastCustom.TYPE_ERROR)
                            })
        }
    }

    private fun getAllDisplayableAccounts(): MutableList<ItemAccount> {
        val mutableList = mutableListOf<ItemAccount>()

        val legacyAddresses = payloadDataManager.legacyAddresses
                .filter { it.tag != LegacyAddress.ARCHIVED_ADDRESS }

        val accounts = payloadDataManager.accounts
                .filter { !it.isArchived }
                .map { it ->
                    val bigIntBalance = payloadDataManager.getAddressBalance(it.xpub)

                    ItemAccount().apply {
                        label = it.label
                        displayBalance = getBalanceString(true, bigIntBalance.toLong())
                        absoluteBalance = bigIntBalance.toLong()
                        accountObject = it
                    }
                }

        // Show "All Accounts" if necessary
        if (accounts.size > 1 || !legacyAddresses.isEmpty()) {
            val all = ConsolidatedAccount().apply {
                label = stringUtils.getString(R.string.all_accounts)
                type = ConsolidatedAccount.Type.ALL_ACCOUNTS
            }

            val bigIntBalance = payloadDataManager.walletBalance

            mutableList.add(ItemAccount().apply {
                label = all.label
                displayBalance = getBalanceString(true, bigIntBalance.toLong())
                absoluteBalance = bigIntBalance.toLong()
                accountObject = all
            })
        }

        mutableList.addAll(accounts)

        // Show "Imported Addresses" if necessary
        if (!legacyAddresses.isEmpty()) {
            val importedAddresses = ConsolidatedAccount().apply {
                label = stringUtils.getString(R.string.imported_addresses)
                type = ConsolidatedAccount.Type.ALL_IMPORTED_ADDRESSES
            }

            val bigIntBalance = payloadDataManager.importedAddressesBalance

            mutableList.add(ItemAccount().apply {
                label = importedAddresses.label
                displayBalance = getBalanceString(true, bigIntBalance.toLong())
                absoluteBalance = bigIntBalance.toLong()
                accountObject = importedAddresses
            })
        }

        return mutableList
    }

    private fun getTransactionsListObservable(itemAccount: ItemAccount) =
            transactionListDataManager.fetchTransactions(itemAccount.accountObject, 50, 0)
                    .doOnNext {
                        view.onTransactionsUpdated(it)
                    }

    private fun getBalanceObservable(itemAccount: ItemAccount) =
            payloadDataManager.updateAllBalances()
                    .doOnComplete {
                        val btcBalance = transactionListDataManager.getBtcBalance(itemAccount.accountObject)
                        val balanceTotal = getBalanceString(isBTC = true, btcBalance = btcBalance)
                        view.onTotalBalanceUpdated(balanceTotal)
                    }.toObservable<Nothing>()

    private fun getUpdateTickerObservable() =
            exchangeRateFactory.updateTicker()
                    .doOnComplete {
                        view.onAccountsUpdated(
                                activeAccountAndAddressList,
                                getLastPrice(getFiatCurrency()),
                                getFiatCurrency(),
                                monetaryUtil
                        )
                        view.onExchangeRateUpdated(exchangeRateFactory.getLastPrice(getFiatCurrency()))
                    }.toObservable<Nothing>()

    private fun getBalanceString(isBTC: Boolean, btcBalance: Long): String {
        val strFiat = prefsUtil.getValue(PrefsUtil.KEY_SELECTED_FIAT, PrefsUtil.DEFAULT_CURRENCY)
        val fiatBalance = exchangeRateFactory.getLastPrice(strFiat) * (btcBalance / 1e8)

        return if (isBTC) {
            monetaryUtil.getDisplayAmountWithFormatting(btcBalance) + " " + getDisplayUnits()
        } else {
            monetaryUtil.getFiatFormat(strFiat).format(fiatBalance) + " " + strFiat
        }
    }

    private fun getLastPrice(fiat: String): Double = exchangeRateFactory.getLastPrice(fiat)

    private fun getDisplayUnits(): String =
            monetaryUtil.btcUnits[prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC)].toString()

    private fun getFiatCurrency(): String =
            prefsUtil.getValue(PrefsUtil.KEY_SELECTED_FIAT, PrefsUtil.DEFAULT_CURRENCY)

    private val monetaryUtil: MonetaryUtil by lazy(LazyThreadSafetyMode.NONE) {
        MonetaryUtil(prefsUtil.getValue(
                PrefsUtil.KEY_BTC_UNITS,
                MonetaryUtil.UNIT_BTC
        ))
    }

    fun onViewFormatChanged(): Unit = TODO("Change the stored view format (isBtc) and update the UI to reflect that")


}
