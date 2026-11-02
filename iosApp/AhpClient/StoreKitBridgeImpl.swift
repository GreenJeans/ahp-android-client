import Foundation
import StoreKit
import AhpClientShared

/// StoreKit 2 bridge implementing `StoreKitBridge` for Milestone 2 (`ahp_pro_unlock`).
/// In Milestone 1 dev builds (`IosAppContainer(debuggable = true)`), Pro is unlocked by default.
final class StoreKitBridgeImpl: NSObject, StoreKitBridge {
    private var updatesTask: Task<Void, Never>?

    func startObservingTransactions() {
        updatesTask?.cancel()
        updatesTask = Task {
            for await verificationResult in Transaction.updates {
                if case .verified(let transaction) = verificationResult {
                    await transaction.finish()
                    let record = StorePurchaseRecord(
                        products: [transaction.productID],
                        state: StorePurchaseState.purchased,
                        acknowledged: true,
                        token: String(transaction.id)
                    )
                    IosAppContainer.companion.shared.billingDriver.notifyTransactionUpdate(purchases: [record])
                }
            }
        }
    }

    func queryEntitlements(productId: String, callback: @escaping ([StorePurchaseRecord]?, String?) -> Void) {
        Task {
            var records: [StorePurchaseRecord] = []
            for await verificationResult in Transaction.currentEntitlements {
                if case .verified(let transaction) = verificationResult, transaction.productID == productId {
                    records.append(
                        StorePurchaseRecord(
                            products: [transaction.productID],
                            state: StorePurchaseState.purchased,
                            acknowledged: true,
                            token: String(transaction.id)
                        )
                    )
                }
            }
            callback(records, nil)
        }
    }

    func purchase(productId: String, callback: @escaping (Int32, [StorePurchaseRecord]?, String?) -> Void) {
        Task {
            do {
                let products = try await Product.products(for: [productId])
                guard let product = products.first else {
                    callback(3, nil, "Product \(productId) not found in App Store Connect")
                    return
                }
                let result = try await product.purchase()
                switch result {
                case .success(let verification):
                    switch verification {
                    case .verified(let transaction):
                        await transaction.finish()
                        let record = StorePurchaseRecord(
                            products: [transaction.productID],
                            state: StorePurchaseState.purchased,
                            acknowledged: true,
                            token: String(transaction.id)
                        )
                        callback(0, [record], nil)
                    case .unverified(_, let error):
                        callback(2, nil, error.localizedDescription)
                    }
                case .userCancelled:
                    callback(1, nil, nil)
                case .pending:
                    let record = StorePurchaseRecord(
                        products: [productId],
                        state: StorePurchaseState.pending,
                        acknowledged: true,
                        token: ""
                    )
                    callback(0, [record], nil)
                @unknown default:
                    callback(2, nil, "Unknown StoreKit purchase result")
                }
            } catch {
                callback(2, nil, error.localizedDescription)
            }
        }
    }
}
