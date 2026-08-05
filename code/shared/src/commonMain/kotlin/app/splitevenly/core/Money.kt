package app.splitevenly

data class Money(val amountSubunits: Long, val currency: String) {
    init {
        require(amountSubunits >= 0)
    }
}
