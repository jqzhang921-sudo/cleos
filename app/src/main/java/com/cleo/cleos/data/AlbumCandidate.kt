package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldAlbumPhotoEntity

/** Gallery metadata is only materialized after the model chooses that one authorized item. */
data class AlbumCandidate(val photo: WorldAlbumPhotoEntity, val materialize: (suspend () -> WorldAlbumPhotoEntity)? = null)
