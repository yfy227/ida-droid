package dev.idadroid.files

import androidx.core.content.FileProvider
import dev.idadroid.R

class RootfsFileProvider : FileProvider(R.xml.rootfs_file_provider_paths)
