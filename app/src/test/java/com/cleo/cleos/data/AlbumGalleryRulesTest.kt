package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AlbumGalleryRulesTest {
    private val album = PhoneAlbum("external_primary", "10", "Chosen")
    private val image = PhonePhoto(album, 3, 10, 300, 100)
    private val snapshot = AlbumGalleryLink(1, 1, "consent", album, GalleryAccess.All, false, setOf(image.key))
    @Test fun snapshotDoesNotIncludeFutureOrEditedPhotos() {
        assertTrue(AlbumGalleryRules.accepts(snapshot, image, GalleryAccess.All))
        for (other in listOf(image.copy(id=4), image.copy(modified=11), image.copy(size=301)))
            assertFalse(AlbumGalleryRules.accepts(snapshot, other, GalleryAccess.All))
    }
    @Test fun futurePhotosRequireExplicitFullAccessConsent() {
        val fresh = image.copy(id=4)
        assertTrue(AlbumGalleryRules.accepts(snapshot.copy(includeFuture=true), fresh, GalleryAccess.All))
        assertFalse(AlbumGalleryRules.accepts(snapshot.copy(includeFuture=true, access=GalleryAccess.Selected), fresh, GalleryAccess.Selected))
    }
    @Test fun grantsCannotWidenOrResumeImplicitly() {
        for (access in listOf(GalleryAccess.Selected, GalleryAccess.None))
            assertFalse(AlbumGalleryRules.accepts(snapshot, image, access))
        assertFalse(AlbumGalleryRules.accepts(snapshot.copy(access=GalleryAccess.Selected), image, GalleryAccess.All))
        assertFalse(AlbumGalleryRules.accepts(snapshot.copy(active=false), image, GalleryAccess.All))
    }
    @Test fun volumeAndBucketAreBothRequiredEvenWithSameImageId() {
        val future = snapshot.copy(includeFuture=true)
        assertFalse(AlbumGalleryRules.accepts(future, image.copy(album=album.copy(bucket="11")), GalleryAccess.All))
        assertFalse(AlbumGalleryRules.accepts(future, image.copy(album=album.copy(volume="other")), GalleryAccess.All))
    }
    @Test fun deviceConsentAndImportOriginsRoundTripTogether() {
        val state = AlbumGalleryState(listOf(snapshot), listOf(AlbumGalleryImport(1,1,album,image.key,"copy.jpg",true)))
        assertEquals(state, Json.decodeFromString<AlbumGalleryState>(Json.encodeToString(state)))
        assertEquals(AlbumGalleryState(), Json.decodeFromString<AlbumGalleryState>("{}"))
    }
    @Test fun backupRetainsGalleryProvenanceButNeverDeviceConsent() {
        val photo = com.cleo.cleos.data.db.WorldAlbumPhotoEntity(1,1,"copy.jpg",10,10,"a".repeat(64),"",1,fromGallery=true)
        val settings = BackupSettings(apiBaseUrl="https://synthetic.invalid/v1",apiModel="synthetic",aiName="TA",userName="User",persona="",historySize=10,
            wallpaper=null,glassMode="liquid",wallpaperDark=null,wallpaperHue=null,wallpaperChroma=null)
        val backup = BackupFile(BackupFile.FORMAT,BackupFile.VERSION,1,settings,emptyList(),emptyList(),emptyList(),emptyList(),worldAlbumPhotos=listOf(photo))
        val raw = Json.encodeToString(backup)
        assertTrue(BackupFile.VERSION>=33)
        assertTrue(Json.decodeFromString<BackupFile>(raw).worldAlbumPhotos.single().fromGallery)
        for (forbidden in listOf("initialKeys","sourceKey","includeFuture","external_primary")) assertFalse(raw.contains(forbidden))
        assertFalse(Json.decodeFromString<com.cleo.cleos.data.db.WorldAlbumPhotoEntity>(Json.encodeToString(photo).replace(",\"fromGallery\":true","")).fromGallery)
    }
}
