package br.com.libraenergia.onsopendata

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.deprecated.openFileSaver
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.collections.listOf
import kotlin.collections.map

sealed class FileOrDir(val name: String) {
    data class File(val filename: String): FileOrDir(filename)
    data class Dir(val dirname: String): FileOrDir(dirname)
}

private const val S3_NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/"

@Serializable
@XmlSerialName("ListBucketResult", S3_NAMESPACE, prefix = "")
data class ListBucketResult(
    @XmlElement(true)
    val Name: String,
    @XmlElement(true)
    val Prefix: String,
    @XmlElement(true)
    val Marker: String? = null,
    @XmlElement(true)
    val MaxKeys: Int,
    @XmlElement(true)
    val Delimiter: String? = null,
    @XmlElement(true)
    val IsTruncated: Boolean,
    @XmlElement(true)
    val Contents: List<S3Object>,
    @XmlSerialName("CommonPrefixes", S3_NAMESPACE, "")
    val CommonPrefixes: List<CommonPrefix> = emptyList()

)

@Serializable
@XmlSerialName("Contents", S3_NAMESPACE, "")
data class S3Object(
    @XmlElement(true)
    val Key: String,
    @XmlElement(true)
    val LastModified: String,
    @XmlElement(true)
    val ETag: String,
    @XmlElement(true)
    val Size: Long,
    @XmlElement(true)
    val StorageClass: String,
    @XmlElement(true)
    val ChecksumAlgorithm: String? = null,
    @XmlElement(true)
    val ChecksumType: String? = null
)

@Serializable
@XmlSerialName("CommonPrefixes", S3_NAMESPACE, "")
data class CommonPrefix(
    @XmlElement(true)
    @XmlSerialName("Prefix", S3_NAMESPACE, "")
    val Prefix: String
)

object S3Service {
    suspend fun list(bucket: String, prefix: String): List<FileOrDir> {
        val response =
            HttpClient().get("https://corsproxy.io/?https://$bucket.s3-us-west-2.amazonaws.com/?delimiter=/&prefix=$prefix").body<String>()
        return XML {
            defaultPolicy {
                ignoreUnknownChildren()
            }
        }.decodeFromString<ListBucketResult>(response).let { response ->
            response.CommonPrefixes.map { it.Prefix }.map { FileOrDir.Dir(it) } +
                    response.Contents.map { it.Key }.map { FileOrDir.File(it) }
        }
    }

    suspend fun download(bucket: String, key: String): ByteArray {
        return HttpClient().get("https://corsproxy.io/?https://$bucket.s3-us-west-2.amazonaws.com/$key").body<ByteArray>()
    }
}

class NavigationViewModel: ViewModel() {
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading
    private val _bucketS3 = MutableStateFlow("ons-aws-prod-opendata")
    val bucketS3: StateFlow<String> = _bucketS3
    private val _path = MutableStateFlow(listOf<String>())
    val path: StateFlow<List<String>> = _path
    private val _currentFolderList = MutableStateFlow(listOf<FileOrDir>())
    val currentFolderList: StateFlow<List<FileOrDir>> = _currentFolderList

    val pathString = path.map { it.joinToString("") { it + "/" } }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _currentFolderList.update {
                S3Service.list(bucketS3.value, pathString.value)
            }
            _loading.value = false
        }
    }

    fun download(filename: String) {
        viewModelScope.launch {
            _loading.value = true
            val file = S3Service.download(bucketS3.value, pathString.value + filename)
            FileKit.openFileSaver(
                suggestedName = filename,
                bytes = file
            )
            _loading.value = false
        }
    }

    fun push(dir: String) {
        _path.update { it + dir.trim('/') }
        load()
    }

    fun pop() {
        _path.update { it.subList(0, it.size - 1) }
        load()
    }
}

@Composable
fun DetailsScreen(viewModel: NavigationViewModel) {
    val bucketS3 by viewModel.bucketS3.collectAsState()
    val path by viewModel.pathString.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val currentFolderList by viewModel.currentFolderList.collectAsState()
    Column {
        Row {
            Button(viewModel::pop) {
                Text("Voltar")
            }
            Text("$bucketS3/$path")
        }

        if (loading) {
            CircularProgressIndicator()
        } else {
            LazyColumn {
                items(currentFolderList) {
                    S3Item(it, viewModel)
                }
            }
        }
    }
}

@Composable
fun S3Item(item: FileOrDir, viewModel: NavigationViewModel) {
    val path by viewModel.pathString.collectAsState()
    val name = item.name.removePrefix(path)
    when(item) {
        is FileOrDir.Dir ->
            Text(
                name,
                modifier = Modifier.clickable(onClick = { viewModel.push(name) })
            )
        is FileOrDir.File ->
            Text(
                name,
                modifier = Modifier.clickable(onClick = { viewModel.download(name) })
            )
    }
}

@Composable
@Preview
fun App() {
    MaterialTheme {
        val viewModel: NavigationViewModel = viewModel { NavigationViewModel() }
        DetailsScreen(viewModel)
    }
}