# <Feature One Time Splits>
## 1. Behavior brief

**Problem.** Currently, if I go out with friends to a restaurant on a random evening, once the bill is paid, the only way to split it is by creating a group with my friends and then splitting the bill. The result of this is a user who does 1 time events very frequently ends up with a long list of groups with a single purchase.
What is broken or missing today, from the user's side. Not the solution.

**User outcome.** A user can create a one time expense e.g Uber ride (split evenly) or restarant bill (split by item), share it with their friends using a link for restaurant bills, and be reimbursed using his payment method e.g Zelle. Paid and The user is also able to see how much they owe, they've been paid back, and are owed for each of their one time expenses and all of them

**Non-goals.** Does not affect the existing "group" flow in which a group is created and expenses are created and tracked. However, do not reinvent how we are doing split calculations or payment tracking.

**Platforms and systems affected.** Android, iOS, `supabase/` , `web/`, `admin/`, `domain/`, `data/`, `ui/`, `platform/`

---

## 2. Acceptance criteria

Observable, given/when/then. Every line here must be provable by something in §4.
- When a user creates a one time expense, it should not appear in his group expenses
- The totals for the one time expenses should be diffrent from the group expenses totals
- When the user sends the link to a one time expense to his friends, since their is no group member notion, the person should add their name before clicking the pay on venmo/zelle button. After paying, the status should switch to Started. The only possible states are None, Started, Complete [these states will need a bit of reasoning since we need to think about 1) when the payee has not been known yet. 2) When they have put in their name and clicked pay and gotten taken to Zelle or whatever payment platform. 3) When they have paid partially and have some pending balance [can be split into i) if they come back to the site or app and click that they have successfully paid. or ii) they clicked pay but did not come back to confirm.... also, we want to reason if we want to consider the payment done if they click pay and then come back to verify (more friction) or 2) if we want to assume that clicking Pay on Zelle counts as paying back (less friction but creates an inconsistency when someone clicks pay and then they think otherwise while in Zelle and end up not paying, and we have already counted it as paid. We will have to weigh the pros and cons of both during spec creation)]]
- Claiming or viewing a one time expense should work on both web and mobile for both authenticated users on the app and guest on the web with no accounts. Clicking a link should redirect the user to the app if they have the app else, stay on the website

