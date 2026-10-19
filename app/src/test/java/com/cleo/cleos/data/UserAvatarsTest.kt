package com.cleo.cleos.data

import com.cleo.cleos.data.db.CompanionEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UserAvatarsTest {
    private fun ta(id: Long=1,avatar: String?=null)=CompanionEntity(id=id,apiBaseUrl="https://example.invalid",apiModel="fixture",createdAt=100,userAvatar=avatar)
    @Test fun oldProfilesInheritTheExistingDefault() {
        val old=Json.decodeFromString<CompanionEntity>("""{"id":1,"apiBaseUrl":"https://example.invalid","apiModel":"fixture","createdAt":100}""")
        assertNull(old.userAvatar)
        assertEquals("default.jpg",UserAvatars.effective(AppSettings(userAvatar="default.jpg"),old))
        assertNull(UserAvatars.effective(AppSettings(),old))
    }
    @Test fun changingTheDefaultCannotOverrideAnotherTasPrivateAvatar() {
        val first=ta(1,"private-one.jpg");val second=ta(2,"private-two.jpg");val inherited=ta(3)
        for(default in listOf("default-one.jpg","default-two.jpg",null)) {
            val settings=AppSettings(userAvatar=default)
            assertEquals("private-one.jpg",UserAvatars.effective(settings,first))
            assertEquals("private-two.jpg",UserAvatars.effective(settings,second))
            assertEquals(default,UserAvatars.effective(settings,inherited))
            assertEquals(default,UserAvatars.effective(settings,null))
        }
    }
    @Test fun resetReturnsToTheCurrentDefault() {
        val settings=AppSettings(userAvatar="new-default.jpg")
        assertEquals("new-default.jpg",UserAvatars.effective(settings,ta(1,"private.jpg").copy(userAvatar=null)))
    }
    @Test fun aPickerKeepsItsOriginalIdentityAndRejectsChangedProfiles() {
        val original=ta(1,"before.jpg");val target=UserAvatars.target(original)
        assertTrue(target.matches(original))
        for(other in listOf(null,ta(2,"before.jpg"),original.copy(createdAt=101),original.copy(userAvatar="new.jpg")))
            assertFalse(target.matches(other))
    }
    @Test fun unsafeOrMissingBackupAvatarFallsBackWithoutReadingOutsideMedia() {
        for(name in listOf("../private.jpg","/private.jpg","a\\b.jpg","..","", ".hidden","a/b.png")) {
            assertNull(UserAvatars.restored(name){fail("Must not inspect an unsafe file");true})
        }
        assertNull(UserAvatars.restored("missing.jpg"){false})
        assertEquals("avatar-test.jpg",UserAvatars.restored("avatar-test.jpg"){true})
        assertEquals("default.jpg",UserAvatars.effective(AppSettings(userAvatar="default.jpg"),ta(1,"../private.jpg")))
    }
    @Test fun independentAvatarsSurviveProfileSerialization() {
        val profiles=listOf(ta(1,"one.jpg"),ta(2,"two.jpg"),ta(3))
        assertEquals(profiles,Json.decodeFromString<List<CompanionEntity>>(Json.encodeToString(profiles)))
    }
    @Test fun defaultAvatarIsRetainedBySettingsMediaChecks() {
        assertTrue("default.jpg" in UserAvatars.settingsFiles(AppSettings(userAvatar="default.jpg")))
    }
}
