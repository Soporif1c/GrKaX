import Foundation
import Testing
@testable import GrKaXKit

/// Skips the calling test unless the core can actually reach the geo data.
///
/// This cannot be arranged from inside the test: the Go runtime snapshots the
/// environment before `main`, so `XRAY_LOCATION_ASSET` has to be exported by
/// whatever launches the process. `apple/scripts/test.sh` does that; a bare
/// `swift test` skips these cases rather than failing them.
func requireGeoData() throws {
    try #require(
        XrayCore.geoDataPresent,
        """
        geo-данные недоступны ядру (искало в \(XrayCore.assetDirectory.path)).
        Запускай тесты через apple/scripts/test.sh
        """
    )
}
