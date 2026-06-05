package da.chelimo.sharecost

data class Money(val amountSubunits: Long, val currency: String) {
    init {
        require(amountSubunits >= 0)
    }
}
