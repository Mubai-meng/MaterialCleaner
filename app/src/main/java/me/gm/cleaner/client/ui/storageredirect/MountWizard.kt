package me.gm.cleaner.client.ui.storageredirect

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Parcel
import android.os.Parcelable
import android.provider.MediaStore.Files.FileColumns
import android.util.Log
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.TextView
import androidx.core.os.ParcelCompat
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.BaseKtListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.InfiniteGridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.R
import me.gm.cleaner.browser.filepicker.FilePickerDialog
import me.gm.cleaner.browser.filepicker.FilePickerDialog.Companion.SelectType.Companion.SELECT_FILE_AND_FOLDER
import me.gm.cleaner.browser.filepicker.FilePickerDialog.Companion.SelectType.Companion.SELECT_FOLDER
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.getPathWithEvent
import me.gm.cleaner.client.getSharedUserIdPackages
import me.gm.cleaner.dao.AppLabelCache
import me.gm.cleaner.dao.ServiceMoreOptionsPreferences
import me.gm.cleaner.databinding.StorageRedirectCategoryMountButtonsWizardBinding
import me.gm.cleaner.databinding.StorageRedirectCategoryMountRuleItemBinding
import me.gm.cleaner.databinding.StorageRedirectCategoryMountWizardQuestionsAccessibleFoldersItemBinding
import me.gm.cleaner.databinding.StorageRedirectCategoryMountWizardQuestionsBinding
import me.gm.cleaner.model.FileSystemEvent
import me.gm.cleaner.util.ClipboardUtils
import me.gm.cleaner.core.common.RuntimeFileUtils
import me.gm.cleaner.util.OpenUtils
import me.gm.cleaner.util.PermissionUtils
import me.gm.cleaner.util.getResourceIdByAttr
import me.gm.cleaner.widget.CheckableLinearLayout
import me.gm.cleaner.widget.recyclerview.ChildItemAnimator
import me.gm.cleaner.widget.recyclerview.DiffArrayList
import me.gm.cleaner.widget.recyclerview.UpdatingList
import me.gm.cleaner.widget.recyclerview.submitDiffList
import java.io.File

class WizardAnswers(
    var q1: Boolean = false,
    var q2: Boolean = false,
    var q3: Boolean = false,
    var q4: Boolean = false,
    var q11: Boolean = false,
    var q12: Boolean = false,
    val accessiblePlacesLiveData: MutableLiveData<DiffArrayList<String?>> =
        MutableLiveData(DiffArrayList(listOf(null))),
    val mountRulesLiveData: MutableLiveData<DiffArrayList<Pair<String?, String?>>> =
        MutableLiveData(DiffArrayList(listOf(null to null))),
    val inaccessiblePlacesLiveData: MutableLiveData<DiffArrayList<String?>> =
        MutableLiveData(DiffArrayList(listOf(null))),
    var rootDirName: String = Q1_ROOT_DIR_FILES,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        ParcelCompat.readBoolean(parcel),
        ParcelCompat.readBoolean(parcel),
        ParcelCompat.readBoolean(parcel),
        ParcelCompat.readBoolean(parcel),
        ParcelCompat.readBoolean(parcel),
        ParcelCompat.readBoolean(parcel),
        MutableLiveData(DiffArrayList(parcel.createStringArrayList()!!)),
        MutableLiveData(
            DiffArrayList(parcel.createStringArrayList()!!.zip(parcel.createStringArrayList()!!))
        ),
        MutableLiveData(DiffArrayList(parcel.createStringArrayList()!!)),
        // 旧格式末尾没有该字段时 readString 返回 null，兜底回 files，保持兼容。
        readRootDirName(parcel),
    )

    fun accessiblePlaces(): List<String> =
        accessiblePlacesLiveData.value!!.run { subList(0, size - 1) } as List<String>

    fun updateAccessiblePlaces(action: DiffArrayList<String?>.() -> Unit) {
        accessiblePlacesLiveData.postValue(accessiblePlacesLiveData.value!!.also { action(it) })
    }

    fun mountRules(): List<Pair<String, String>> =
        mountRulesLiveData.value!!.run { subList(0, size - 1) } as List<Pair<String, String>>

    fun updateMountRules(action: DiffArrayList<Pair<String?, String?>>.() -> Unit) {
        mountRulesLiveData.postValue(mountRulesLiveData.value!!.also { action(it) })
    }

    fun inaccessiblePlaces(): List<String> =
        inaccessiblePlacesLiveData.value!!.run { subList(0, size - 1) } as List<String>

    fun updateInaccessiblePlaces(action: DiffArrayList<String?>.() -> Unit) {
        inaccessiblePlacesLiveData.postValue(inaccessiblePlacesLiveData.value!!.also { action(it) })
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WizardAnswers) return false
        return q1 == other.q1 && q2 == other.q2 && q3 == other.q3 &&
                q4 == other.q4 && q11 == other.q11 && q12 == other.q12 &&
                rootDirName == other.rootDirName &&
                accessiblePlaces() == other.accessiblePlaces() &&
                mountRules() == other.mountRules() &&
                inaccessiblePlaces() == other.inaccessiblePlaces()
    }

    override fun hashCode(): Int {
        var result = q1.hashCode()
        result = 31 * result + q2.hashCode()
        result = 31 * result + q3.hashCode()
        result = 31 * result + q4.hashCode()
        result = 31 * result + q11.hashCode()
        result = 31 * result + q12.hashCode()
        result = 31 * result + rootDirName.hashCode()
        result = 31 * result + accessiblePlaces().hashCode()
        result = 31 * result + mountRules().hashCode()
        result = 31 * result + inaccessiblePlaces().hashCode()
        return result
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        ParcelCompat.writeBoolean(parcel, q1)
        ParcelCompat.writeBoolean(parcel, q2)
        ParcelCompat.writeBoolean(parcel, q3)
        ParcelCompat.writeBoolean(parcel, q4)
        ParcelCompat.writeBoolean(parcel, q11)
        ParcelCompat.writeBoolean(parcel, q12)
        parcel.writeStringList(accessiblePlacesLiveData.value)
        val mountRulesUnzipped = mountRulesLiveData.value!!.unzip()
        parcel.writeStringList(mountRulesUnzipped.first)
        parcel.writeStringList(mountRulesUnzipped.second)
        parcel.writeStringList(inaccessiblePlacesLiveData.value)
        parcel.writeString(rootDirName)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<WizardAnswers> {
        const val Q1_ROOT_DIR_FILES: String = "files"
        const val Q1_ROOT_DIR_SANDBOX: String = "sdcard"

        // 非内联：构造器委托阶段不可调用 inline 函数（Cannot access ... before the instance has been initialized）。
        private fun readRootDirName(parcel: Parcel): String = try {
            parcel.readString() ?: Q1_ROOT_DIR_FILES
        } catch (e: Throwable) {
            Q1_ROOT_DIR_FILES
        }

        override fun createFromParcel(parcel: Parcel): WizardAnswers {
            val startPosition = parcel.dataPosition()
            try {
                return WizardAnswers(parcel)
            } catch (e: Throwable) {
                Log.w("MountWizard", "new-format WizardAnswers parse failed, try legacy 5-bool", e)
                parcel.setDataPosition(startPosition)
                return readLegacyFormat(parcel)
            }
        }

        override fun newArray(size: Int): Array<WizardAnswers?> = arrayOfNulls(size)

        /**
         * 兼容 Issue #4 之前漏写 q4 的旧 Base64：旧格式只有
         * q1,q2,q3,q11,q12 共 5 个 boolean，后面 4 个 StringList 不变。
         * 旧数据无 q4，一律按 false 恢复。
         */
        private fun readLegacyFormat(parcel: Parcel): WizardAnswers {
            val q1 = ParcelCompat.readBoolean(parcel)
            val q2 = ParcelCompat.readBoolean(parcel)
            val q3 = ParcelCompat.readBoolean(parcel)
            val q11 = ParcelCompat.readBoolean(parcel)
            val q12 = ParcelCompat.readBoolean(parcel)
            val accessible = MutableLiveData(DiffArrayList(parcel.createStringArrayList()!!))
            val sources = parcel.createStringArrayList()!!
            val targets = parcel.createStringArrayList()!!
            val mountRules = MutableLiveData(DiffArrayList(sources.zip(targets)))
            val inaccessible = MutableLiveData(DiffArrayList(parcel.createStringArrayList()!!))
            return WizardAnswers(
                q1 = q1, q2 = q2, q3 = q3, q4 = false, q11 = q11, q12 = q12,
                accessiblePlacesLiveData = accessible,
                mountRulesLiveData = mountRules,
                inaccessiblePlacesLiveData = inaccessible,
            )
        }
    }
}

data class AutoAnswerRationale(
    val recordCount: Int,
    val q1Reasons: List<FileSystemEvent>,
    val accessiblePlacesReasons: List<List<FileSystemEvent>>,
    val mountRulesReasons: List<List<FileSystemEvent>>,
    val inaccessiblePlacesReasons: List<FileSystemEvent>,
)

class MountWizard(private val packageInfo: PackageInfo) {
    private val packageName: String = packageInfo.packageName
    private val initialAnswers: WizardAnswers
        get() = WizardAnswers(
            q12 = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    packageInfo.applicationInfo.category and ApplicationInfo.CATEGORY_GAME != 0 ||
                    packageInfo.applicationInfo.flags and ApplicationInfo.FLAG_IS_GAME != 0) ||
                    CleanerClient.service?.createFileModel(obbDir)?.isDirectory ?: false,
            rootDirName = ServiceMoreOptionsPreferences.wizardRootDirName
        )

    // BIND
    private lateinit var wizardBinding: StorageRedirectCategoryMountWizardQuestionsBinding
    private lateinit var answers: WizardAnswers
    fun bindView(
        mountRules: List<Pair<String, String>>,
        binding: StorageRedirectCategoryMountWizardQuestionsBinding,
        fragment: StorageRedirectFragment
    ) {
        val isFirstTimeBinding = !::wizardBinding.isInitialized
        wizardBinding = binding
        if (isFirstTimeBinding) {
            answers = if (mountRules.isNotEmpty()) {
                retrodictAnswers(mountRules)
            } else {
                initialAnswers
            }
        }
        bindView(answers, binding, fragment)
    }

    fun bindButton(
        binding: StorageRedirectCategoryMountButtonsWizardBinding,
        fragment: StorageRedirectFragment,
        appTypeMarks: () -> AppCategory?,
    ) {
        binding.autoCompleteByRecord.isVisible = CleanerClient.pingBinder()
        binding.autoCompleteByRecord.setOnClickListener {
            fragment.lifecycleScope.launch {
                val record = withContext(Dispatchers.IO) {
                    CleanerClient.service?.queryDistinctRecordsInclude(arrayOf(packageName))?.list ?: emptyList()
                }
                val rationale = withContext(Dispatchers.Default) {
                    answerBasedOnRecord(answers, record, appTypeMarks())
                }
                val title = fragment.getString(
                    R.string.auto_complete_by_record_rationale_title, rationale.recordCount
                )
                val rationales = arrayListOf<Pair<String, String>>()
                if (rationale.q1Reasons.isNotEmpty()) {
                    rationales += fragment.getString(
                        R.string.auto_complete_by_record_rationale_content_header,
                        fragment.getString(R.string.storage_redirect_question_1)
                    ) to rationale.q1Reasons.joinToString("\n") { event ->
                        getPathWithEvent(event.path, event.flags, fragment)
                    }
                }
                if (rationale.accessiblePlacesReasons.isNotEmpty()) {
                    rationales += fragment.getString(
                        R.string.auto_complete_by_record_rationale_content_header,
                        fragment.getString(R.string.storage_redirect_question_2)
                    ) to rationale.accessiblePlacesReasons.mapIndexed { index, list ->
                        index.toString() + "\n" + list.joinToString("\n") { event ->
                            getPathWithEvent(event.path, event.flags, fragment)
                        }
                    }.joinToString("\n")
                }
                if (rationale.mountRulesReasons.isNotEmpty()) {
                    rationales += fragment.getString(
                        R.string.auto_complete_by_record_rationale_content_header,
                        fragment.getString(R.string.storage_redirect_question_3)
                    ) to rationale.mountRulesReasons.mapIndexed { index, list ->
                        index.toString() + "\n" + list.joinToString("\n") { event ->
                            getPathWithEvent(event.path, event.flags, fragment)
                        }
                    }.joinToString("\n")
                }
                if (rationale.inaccessiblePlacesReasons.isNotEmpty()) {
                    rationales += fragment.getString(
                        R.string.auto_complete_by_record_rationale_content_header,
                        fragment.getString(R.string.storage_redirect_question_4)
                    ) to rationale.inaccessiblePlacesReasons.joinToString("\n") { event ->
                        getPathWithEvent(event.path, event.flags, fragment)
                    }
                }
                RationaleDialog
                    .newInstance(title, rationales)
                    .show(fragment.childFragmentManager, null)
                if (::wizardBinding.isInitialized) {
                    restoreCheckedState(wizardBinding, answers)
                }
            }
        }
    }

    companion object {

        fun bindView(
            answers: WizardAnswers,
            binding: StorageRedirectCategoryMountWizardQuestionsBinding,
            fragment: Fragment
        ) {
            fun CheckableLinearLayout.maybePost(
                adapter: BaseKtListAdapter<*, *>, newSize: Int, action: Runnable
            ) {
                val oldSize = adapter.currentList.size
                if (isChecked || newSize <= 1) {
                    action.run()
                } else {
                    if (oldSize <= 1) {
                        // workaround item not rebound when child hidden
                        adapter.notifyItemChanged(0)
                    }
                    postOnAnimation(action)
                }
            }

            val context = fragment.requireContext()
            val inflater = LayoutInflater.from(context)
            val switchWidgetLayout = context.getResourceIdByAttr(R.attr.switchWidgetLayoutStyle)
            inflater.inflate(switchWidgetLayout, binding.widgetFrame1)
            inflater.inflate(switchWidgetLayout, binding.widgetFrame2)
            restoreCheckedState(binding, answers)

            binding.q1.setOnClickListener {
                answers.q1 = binding.q1.isChecked
                bindRootDirToggle(binding, answers)
            }
            binding.q11.setOnClickListener {
                answers.q11 = binding.q11.isChecked
                bindRootDirToggle(binding, answers)
            }
            binding.q12.setOnClickListener {
                answers.q12 = binding.q12.isChecked
            }

            binding.q2.setOnClickListener {
                answers.q2 = binding.q2.isChecked
            }
            val q2List = binding.q2List
            val q2Adapter = PathsAdapter(fragment, answers, SELECT_FOLDER)
            q2List.adapter = q2Adapter
            q2List.layoutManager = InfiniteGridLayoutManager(fragment.requireContext(), 1)
            q2List.setHasFixedSize(true)
            q2List.itemAnimator = ChildItemAnimator(q2List)
            val accessiblePlacesObserver = Observer<DiffArrayList<String?>> { accessiblePlaces ->
                binding.q2.maybePost(q2Adapter, accessiblePlaces.size) {
                    q2Adapter.submitDiffList(accessiblePlaces)
                }
            }
            answers.accessiblePlacesLiveData.removeObserver(accessiblePlacesObserver)
            answers.accessiblePlacesLiveData.observe(fragment.viewLifecycleOwner, accessiblePlacesObserver)

            binding.q3.setOnClickListener {
                answers.q3 = binding.q3.isChecked
            }
            val q3List = binding.q3List
            val q3Adapter = RedirectAdapter(fragment, answers)
            q3List.adapter = q3Adapter
            q3List.layoutManager = InfiniteGridLayoutManager(fragment.requireContext(), 1)
            q3List.setHasFixedSize(true)
            q3List.itemAnimator = ChildItemAnimator(q3List)
            val mountRulesObserver = Observer<DiffArrayList<Pair<String?, String?>>> { mountRules ->
                binding.q3.maybePost(q3Adapter, mountRules.size) {
                    q3Adapter.submitDiffList(mountRules)
                }
            }
            answers.mountRulesLiveData.removeObserver(mountRulesObserver)
            answers.mountRulesLiveData.observe(fragment.viewLifecycleOwner, mountRulesObserver)

            binding.q4.setOnClickListener {
                answers.q4 = binding.q4.isChecked
            }
            val q4List = binding.q4List
            val q4Adapter = PathsAdapter(fragment, answers, SELECT_FILE_AND_FOLDER)
            q4List.adapter = q4Adapter
            q4List.layoutManager = InfiniteGridLayoutManager(fragment.requireContext(), 1)
            q4List.setHasFixedSize(true)
            q4List.itemAnimator = ChildItemAnimator(q4List)
            val inaccessiblePlacesObserver = Observer<DiffArrayList<String?>> { inaccessiblePlaces ->
                binding.q4.maybePost(q4Adapter, inaccessiblePlaces.size) {
                    q4Adapter.submitDiffList(inaccessiblePlaces)
                }
            }
            answers.inaccessiblePlacesLiveData.removeObserver(inaccessiblePlacesObserver)
            answers.inaccessiblePlacesLiveData.observe(fragment.viewLifecycleOwner, inaccessiblePlacesObserver)
        }

        private fun restoreCheckedState(
            binding: StorageRedirectCategoryMountWizardQuestionsBinding, answers: WizardAnswers
        ) {
            binding.q1.setCheckedNoAnim(answers.q1)
            binding.q2.setCheckedNoAnim(answers.q2)
            binding.q3.setCheckedNoAnim(answers.q3)
            binding.q4.setCheckedNoAnim(answers.q4)
            binding.q11.isChecked = answers.q11
            binding.q12.isChecked = answers.q12
            bindRootDirToggle(binding, answers)
        }

        /**
         * 逐应用根目录 toggle（q1 && q11 才可见）。
         * 切换仅写 answers.rootDirName，提交时 createRules 自然分叉，
         * 迁移提示走现有 getRecommendDirOps，不另起迁移逻辑。
         */
        private fun bindRootDirToggle(
            binding: StorageRedirectCategoryMountWizardQuestionsBinding, answers: WizardAnswers
        ) {
            val root = binding.root
            val row = root.findViewById<View>(R.id.q_1_1_root_dir) ?: return
            row.isVisible = answers.q1 && answers.q11
            if (!row.isVisible) {
                return
            }
            val isSandbox = answers.rootDirName == WizardAnswers.Q1_ROOT_DIR_SANDBOX
            val filesOption = root.findViewById<View>(R.id.root_dir_files)
            val sdcardOption = root.findViewById<View>(R.id.root_dir_sdcard)
            (filesOption as? Checkable)?.isChecked = !isSandbox
            (sdcardOption as? Checkable)?.isChecked = isSandbox
            val hint = root.findViewById<TextView>(R.id.root_dir_hint)
            hint?.isVisible = isSandbox
            filesOption?.setOnClickListener {
                answers.rootDirName = WizardAnswers.Q1_ROOT_DIR_FILES
                bindRootDirToggle(binding, answers)
            }
            sdcardOption?.setOnClickListener {
                answers.rootDirName = WizardAnswers.Q1_ROOT_DIR_SANDBOX
                bindRootDirToggle(binding, answers)
            }
        }
    }

    // SUBMIT
    private val sdDir: String = RuntimeFileUtils.externalStorageDir.path
    private val dataDir: String = RuntimeFileUtils.androidDataDir.resolve(packageName).path
    private val mediaDir: String = RuntimeFileUtils.androidMediaDir.resolve(packageName).path
    private val obbDir: String = RuntimeFileUtils.androidObbDir.resolve(packageName).path
    private val sandboxDir: String = RuntimeFileUtils.androidSandboxDir.resolve(packageName).path
    private val filesDir: String = File(dataDir, "files").path
    private val cacheDir: String = File(dataDir, "cache").path

    // 承载 q1 根目录重定向的专用子目录：Android/data/<pkg>/sdcard
    private val sandboxRootDir: String = File(dataDir, WizardAnswers.Q1_ROOT_DIR_SANDBOX).path

    fun createRules(): List<Pair<String, String>> = createRules(answers)

    private val inaccessibleMarker: Pair<String, String> by lazy { cacheDir to sdDir }

    fun createRules(answers: WizardAnswers): List<Pair<String, String>> {
        val rules = mutableListOf<Pair<String, String>>()
        if (answers.q1) {
            rules += if (answers.q11) {
                // q11 开启且选择专用承载目录时，把根挂到 Android/data/<pkg>/sdcard。
                if (answers.rootDirName == WizardAnswers.Q1_ROOT_DIR_SANDBOX) {
                    sandboxRootDir to sdDir
                } else {
                    filesDir to sdDir
                }
            } else {
                cacheDir to sdDir
            }
            rules += dataDir to dataDir
            if (answers.q12) {
                rules += obbDir to obbDir
            }
        }
        if (answers.q2) {
            answers.accessiblePlaces().forEach { dir ->
                rules += dir to dir
            }
        }
        if (answers.q3) {
            answers.mountRules().forEach { (source, target) ->
                rules += source to target
            }
        }
        if (answers.q4) {
            val previousRules = rules.toList()
            answers.inaccessiblePlaces().forEach { dir ->
                RedirectReachabilityAnalyzer.accessiblePlaces(previousRules, dir).forEach { accessiblePlace ->
                    rules += RedirectReachabilityAnalyzer.mountedPath(
                        listOf(inaccessibleMarker), accessiblePlace
                    ) to accessiblePlace
                }
            }
        }
        if (answers.q1) {
            getSharedUserIdPackages(packageInfo)
                .asSequence()
                .filter { it.packageName != packageName }
                .forEach { otherPackageInfo ->
                    val otherDataDir =
                        RuntimeFileUtils.androidDataDir.resolve(otherPackageInfo.packageName).path
                    if (!rules.contains(otherDataDir to otherDataDir)) {
                        rules += otherDataDir to otherDataDir
                    }
                    if (answers.q12) {
                        val otherObbDir =
                            RuntimeFileUtils.androidObbDir.resolve(otherPackageInfo.packageName).path
                        if (!rules.contains(otherObbDir to otherObbDir)) {
                            rules += otherObbDir to otherObbDir
                        }
                    }
                }
        }
        return rules
    }

    fun retrodictAnswers(mountRules: List<Pair<String, String>>): WizardAnswers {
        val answers = WizardAnswers()
        // P0：createRules 在尾部追加 sharedUid 自对规则（otherDataDir/otherObbDir），
        // 旧 retrodict 未剥离，尾部自对会被误判为 q3 自定义，导致 round-trip 腐败。
        // 这里先剥离确定性 sharedUid 尾部，createRules 会按 q1/q12 重新生成。
        val sharedUidPairs = try {
            getSharedUserIdPackages(packageInfo)
                .asSequence()
                .filter { it.packageName != packageName }
                .flatMap { otherPackageInfo ->
                    sequenceOf(
                        RuntimeFileUtils.androidDataDir.resolve(otherPackageInfo.packageName).path to
                                RuntimeFileUtils.androidDataDir.resolve(otherPackageInfo.packageName).path,
                        RuntimeFileUtils.androidObbDir.resolve(otherPackageInfo.packageName).path to
                                RuntimeFileUtils.androidObbDir.resolve(otherPackageInfo.packageName).path,
                    )
                }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        var rulesNotBacktracked = if (sharedUidPairs.isEmpty()) {
            mountRules
        } else {
            mountRules.filterNot { it in sharedUidPairs }
        }
        // backtrack q1
        var q1Size = 0
        if (rulesNotBacktracked.size >= 2 && rulesNotBacktracked[1] == dataDir to dataDir) {
            if (rulesNotBacktracked[0] == cacheDir to sdDir) {
                answers.q1 = true
                answers.rootDirName = WizardAnswers.Q1_ROOT_DIR_FILES
            } else if (rulesNotBacktracked[0] == filesDir to sdDir) {
                answers.q1 = true
                answers.q11 = true
                answers.rootDirName = WizardAnswers.Q1_ROOT_DIR_FILES
            } else if (rulesNotBacktracked[0] == sandboxRootDir to sdDir) {
                answers.q1 = true
                answers.q11 = true
                answers.rootDirName = WizardAnswers.Q1_ROOT_DIR_SANDBOX
            }
            if (answers.q1) {
                q1Size += 2
                if (rulesNotBacktracked.size >= 3 && rulesNotBacktracked[2] == obbDir to obbDir) {
                    answers.q12 = true
                    q1Size++
                }
            }
        }
        rulesNotBacktracked = rulesNotBacktracked.subList(q1Size, rulesNotBacktracked.size)

        // backtrack q2
        val q2Backtracked = mutableListOf<String>()
        for ((source, target) in rulesNotBacktracked) {
            if (source == target) {
                q2Backtracked += target
            } else {
                break
            }
        }
        if (q2Backtracked.isNotEmpty()) {
            answers.q2 = true
            answers.updateAccessiblePlaces {
                addAll(0, q2Backtracked)
            }
            rulesNotBacktracked =
                rulesNotBacktracked.subList(q2Backtracked.size, rulesNotBacktracked.size)
        }

        // backtrack q4
        val reversed = rulesNotBacktracked.asReversed().toMutableList().listIterator()
        val q4Backtracked = mutableListOf<String>()
        while (reversed.hasNext()) {
            val (source, target) = reversed.next()
            if (source == RedirectReachabilityAnalyzer.mountedPath(listOf(inaccessibleMarker), target)) {
                q4Backtracked += target
                reversed.remove()
            } else {
                reversed.previous()
                break
            }
        }
        val previousRules = mountRules.subList(0, mountRules.size - q4Backtracked.size)
        if (q4Backtracked.isNotEmpty()) {
            answers.q4 = true
            answers.updateInaccessiblePlaces {
                addAll(
                    0,
                    q4Backtracked
                        .map { path -> RedirectReachabilityAnalyzer.mountedPath(previousRules, path) }
                        .distinct()
                        .asReversed()
                )
            }
            rulesNotBacktracked =
                rulesNotBacktracked.subList(0, rulesNotBacktracked.size - q4Backtracked.size)
        }

        // backtrack q3
        if (rulesNotBacktracked.isNotEmpty()) {
            answers.q3 = true
            for ((source, target) in rulesNotBacktracked) {
                answers.updateMountRules {
                    add(size - 1, source to target)
                }
            }
        }
        return answers
    }

    private val speciallyAllowedDirs: List<String> by lazy {
        val speciallyAllowedDirs = mutableSetOf<String>()
        RuntimeFileUtils.defaultExternalNoScan.forEach { dir ->
            var parent = dir
            do {
                if (!speciallyAllowedDirs.add(parent.path)) {
                    break
                }
                parent = parent.parentFile!!
            } while (RuntimeFileUtils.startsWith(RuntimeFileUtils.externalStorageDirParent, parent))
        }
        speciallyAllowedDirs.toList()
    }

    fun answerBasedOnRecord(
        answers: WizardAnswers, record: List<FileSystemEvent>, appTypeMarks: AppCategory?
    ): AutoAnswerRationale {
        // load record
        val hasStoragePermissions = PermissionUtils.containsStoragePermissions(packageInfo)
        val label = AppLabelCache.getPackageLabel(packageInfo)
        var usefulRecords = record.asSequence()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Writing files inside androidDataDir or androidObbDir will never succeed on R,
            // despite whether apps have storage permission.
            usefulRecords = usefulRecords.filterNot { event ->
                val path = RuntimeFileUtils.getPathAsUser(event.path, 0)
                arrayOf(RuntimeFileUtils.androidDataDir, RuntimeFileUtils.androidObbDir).any { dir ->
                    RuntimeFileUtils.startsWith(dir, path)
                }
            }
        }
        if (!hasStoragePermissions) {
            // If an app doesn't have storage permission,
            // writing files outside of standard dirs will never succeed.
            val standardParents = RuntimeFileUtils.standardDirs
                .map { File(sdDir, it) } + RuntimeFileUtils.androidDir
            usefulRecords = usefulRecords.filter { event ->
                val path = RuntimeFileUtils.getPathAsUser(event.path, 0)
                standardParents.any { standardDir ->
                    RuntimeFileUtils.startsWith(standardDir, path)
                }
            }
        }
        if (appTypeMarks != null && ServiceMoreOptionsPreferences.autoCompleteByRecordRespect) {
            when (appTypeMarks.type) {
                AppType.DOWNLOAD -> {
                    val marks = appTypeMarks.marks
                    usefulRecords = usefulRecords.filterNot { event ->
                        val path = RuntimeFileUtils.getPathAsUser(event.path, 0)
                        marks.any { mark -> RuntimeFileUtils.startsWith(mark, path) }
                    }
                }

                AppType.ALL_FILES_ACCESS -> {
                    usefulRecords = usefulRecords.filter { false } // clear()
                }
            }
        }
        usefulRecords = usefulRecords
            // allow writing files inside obbDir and mediaDir
            .filterNot { event ->
                val path = RuntimeFileUtils.getPathAsUser(event.path, 0)
                RuntimeFileUtils.startsWith(mediaDir, path) ||
                        RuntimeFileUtils.startsWith(obbDir, path) ||
                        RuntimeFileUtils.startsWith(sandboxDir, path)
            }
            // allow create speciallyAllowedDirs
            .filterNot { event ->
                val path = RuntimeFileUtils.getPathAsUser(event.path, 0)
                event.flags and 0x40000000 != 0 && event.flags and FileObserver.CREATE != 0 &&
                        speciallyAllowedDirs.any { it.equals(path, true) }
            }
        val partition = usefulRecords.partition { event ->
            event.flags and 0x40000000 != 0
        }
        val dirRecords = partition.first
        val fileRecords = partition.second
        val q1Reasons = mutableListOf<FileSystemEvent>()
        val accessiblePlaces = mutableListOf<String>()
        val accessiblePlacesReasons = mutableListOf<MutableList<FileSystemEvent>>()
        val mountRules = mutableListOf<Pair<String, String>>()
        val mountRulesReasons = mutableListOf<MutableList<FileSystemEvent>>()
        // fileRecords
        // This can't be refactored with groupBy() because we need to extract reason.
        val mediaPaths = SparseArray<MutableList<FileSystemEvent>>()
        fileRecords.forEach { event ->
            when (val mediaType =
                MimeUtils.resolveMediaType(MimeUtils.resolveMimeType(File(event.path)))) {
                FileColumns.MEDIA_TYPE_AUDIO,
                FileColumns.MEDIA_TYPE_VIDEO,
                FileColumns.MEDIA_TYPE_IMAGE/*,
                FileColumns.MEDIA_TYPE_DOCUMENT*/ -> {
                    val events = mediaPaths[mediaType] ?: mutableListOf()
                    events += event
                    mediaPaths.put(mediaType, events)
                }

                else -> if (getRecommendDirs(mediaType)
                        .none { dir -> RuntimeFileUtils.startsWith(dir, event.path) }
                ) {
                    q1Reasons += event
                }
            }
        }
        arrayOf(
            FileColumns.MEDIA_TYPE_AUDIO to Environment.DIRECTORY_MUSIC,
            FileColumns.MEDIA_TYPE_VIDEO to Environment.DIRECTORY_MOVIES,
            FileColumns.MEDIA_TYPE_IMAGE to Environment.DIRECTORY_PICTURES,
//            FileColumns.MEDIA_TYPE_DOCUMENT to Environment.DIRECTORY_DOCUMENTS,
        ).forEach { (mediaType, publicDir) ->
            val events = mediaPaths[mediaType] ?: emptyList()
            events.forEach { event ->
                val path = RuntimeFileUtils.getPathAsUser(event.path, 0)
                val recommendDir = getRecommendDirs(mediaType)
                    .firstOrNull { dir -> RuntimeFileUtils.startsWith(dir, path) }
                if (recommendDir != null) {
                    val mergeIndex = accessiblePlaces.indexOfFirst { dir ->
                        RuntimeFileUtils.startsWith(dir, path)
                    }
                    if (mergeIndex == -1) {
                        accessiblePlaces += recommendDir.path
                        accessiblePlacesReasons += mutableListOf(event)
                    } else {
                        accessiblePlacesReasons[mergeIndex] += event
                    }
                } else if (
                // ignore open event
                    event.flags and FileObserver.OPEN == 0 ||
                    event.flags and FileObserver.CREATE != 0
                ) {
                    val redirectSource = sdDir + File.separator +
                            publicDir + File.separator + label
                    var parent = File(path)
                    var mergeIndex: Int
                    do {
                        parent = parent.parentFile!!
                        mergeIndex = mountRules.indexOfFirst { (source, target) ->
                            redirectSource == source && RuntimeFileUtils.startsWith(parent, target)
                        }
                    } while (mergeIndex == -1 &&
                        RuntimeFileUtils.childOf(sdDir, parent.parent!!)
                    )
                    if (mergeIndex == -1) {
                        mountRules += redirectSource to File(path).parent!!
                        mountRulesReasons += mutableListOf(event)
                    } else {
                        mountRules[mergeIndex] = redirectSource to parent.path
                        mountRulesReasons[mergeIndex] += event
                    }
                }
            }
        }
        // If a dir is redirected to multiple dirs,
        // redirect it to Download dir and remove the other redirections.
        val targetsNeedMerge = mountRules.groupBy { (source, target) -> target }
            .filter { it.value.size > 1 }
            .keys
        val targetsNeedMergeToReasons = targetsNeedMerge
            .associateWith { mutableListOf<FileSystemEvent>() }
        val indicesNeedMerge = mountRules
            .mapIndexedNotNull { index, (source, target) ->
                if (target in targetsNeedMerge) index else null
            }
            .asReversed()
            .forEach { index ->
                val target = mountRules.removeAt(index).second
                targetsNeedMergeToReasons[target]?.let { it += mountRulesReasons.removeAt(index) }
                            ?: Log.w("MountWizard", "target $target not found in targetsNeedMergeToReasons")
            }
        val downloadDir = sdDir + File.separator +
                Environment.DIRECTORY_DOWNLOADS + File.separator + label
        mountRules += targetsNeedMergeToReasons.keys.map { target -> downloadDir to target }
        mountRulesReasons += targetsNeedMergeToReasons.values
        // If disallowed file in q1Reasons is allowed in accessiblePlaces, disallow them in inaccessiblePlaces.
        val inaccessiblePlaces = mutableListOf<String>()
        val inaccessiblePlacesReasons = mutableListOf<FileSystemEvent>()
        val q1Iterator = q1Reasons.iterator()
        while (q1Iterator.hasNext()) {
            val event = q1Iterator.next()
            val path = RuntimeFileUtils.getPathAsUser(event.path, 0)
            if (accessiblePlaces.any { dir -> RuntimeFileUtils.childOf(dir, path) }) {
                val mergeIndex = inaccessiblePlaces.indexOfFirst { dir ->
                    RuntimeFileUtils.startsWith(dir, path) || RuntimeFileUtils.startsWith(path, dir)
                }
                if (mergeIndex == -1) {
                    inaccessiblePlaces += path
                } else {
                    if (RuntimeFileUtils.childOf(path, inaccessiblePlaces[mergeIndex])) {
                        inaccessiblePlaces[mergeIndex] = path
                    }
                }
                inaccessiblePlacesReasons += event
                q1Iterator.remove()
            }
        }
        // dirRecords
        dirRecords.forEach { event ->
            val path = RuntimeFileUtils.getPathAsUser(event.path, 0)
            if (mountRules.none { (source, target) -> RuntimeFileUtils.startsWith(target, path) } &&
                accessiblePlaces.none { dir -> RuntimeFileUtils.startsWith(dir, path) }
            ) {
                q1Reasons += event
            }
        }
        // result
        if (appTypeMarks != null && ServiceMoreOptionsPreferences.autoCompleteByRecordRespect) {
            val marks = appTypeMarks.marks
            when (appTypeMarks.type) {
                AppType.DOWNLOAD -> {
                    val targets = answers.mountRules().unzip().second
                    mountRules += marks
                        .filter { mark ->
                            targets.none { target -> RuntimeFileUtils.startsWith(mark, target) }
                        }
                        .map { mark -> downloadDir to mark }
                }

                AppType.ALL_FILES_ACCESS -> {
                    if (!ServiceMoreOptionsPreferences.autoCompleteByRecordMerge) {
                        answers.q1 = false
                    }
                    inaccessiblePlaces += marks
                }
            }
        }
        if (!ServiceMoreOptionsPreferences.autoCompleteByRecordMerge) {
            answers.q1 = q1Reasons.isNotEmpty()
            answers.q12 = initialAnswers.q12

            answers.q2 = accessiblePlaces.isNotEmpty()
            answers.updateAccessiblePlaces {
                clear()
                addAll(accessiblePlaces)
                add(null)
            }

            answers.q3 = mountRules.isNotEmpty()
            answers.updateMountRules {
                clear()
                addAll(mountRules)
                add(null to null)
            }

            answers.q4 = inaccessiblePlaces.isNotEmpty()
            answers.updateInaccessiblePlaces {
                clear()
                addAll(inaccessiblePlaces)
                add(null)
            }
        } else {
            answers.q1 = answers.q1 || q1Reasons.isNotEmpty()
            answers.q12 = answers.q12 || initialAnswers.q12

            answers.q2 = answers.q2 || accessiblePlaces.isNotEmpty()
            answers.updateAccessiblePlaces {
                addAll(size - 1, accessiblePlaces.filter { it !in this })
            }

            answers.q3 = answers.q3 || mountRules.isNotEmpty()
            answers.updateMountRules {
                addAll(size - 1, mountRules.filter { it !in this })
            }

            answers.q4 = answers.q4 || inaccessiblePlaces.isNotEmpty()
            answers.updateInaccessiblePlaces {
                addAll(size - 1, inaccessiblePlaces.filter { it !in this })
            }
        }
        return AutoAnswerRationale(
            CleanerClient.service?.countRecordsInclude(arrayOf(packageName)) ?: 0,
            q1Reasons, accessiblePlacesReasons, mountRulesReasons, inaccessiblePlacesReasons
        )
    }

    fun getRecommendDirs(mediaType: Int): List<File> {
        val recommendDirs = when (mediaType) {
            FileColumns.MEDIA_TYPE_PLAYLIST -> RuntimeFileUtils.standardDirs.map {
                File(sdDir, it)
            }

            FileColumns.MEDIA_TYPE_SUBTITLE -> RuntimeFileUtils.standardDirs.map {
                File(sdDir, it)
            }

            FileColumns.MEDIA_TYPE_AUDIO -> mutableListOf(
                File(sdDir, Environment.DIRECTORY_MUSIC),
                File(sdDir, Environment.DIRECTORY_PODCASTS),
                File(sdDir, Environment.DIRECTORY_NOTIFICATIONS),
                File(sdDir, Environment.DIRECTORY_ALARMS),
                File(sdDir, Environment.DIRECTORY_RINGTONES),
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(File(sdDir, Environment.DIRECTORY_AUDIOBOOKS))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(File(sdDir, Environment.DIRECTORY_RECORDINGS))
                }
            }

            FileColumns.MEDIA_TYPE_VIDEO -> listOf(
                File(sdDir, Environment.DIRECTORY_MOVIES),
            )

            FileColumns.MEDIA_TYPE_IMAGE -> listOf(
                File(sdDir, Environment.DIRECTORY_PICTURES),
                File(sdDir, Environment.DIRECTORY_DCIM),
            )

            FileColumns.MEDIA_TYPE_DOCUMENT -> listOf(
                File(sdDir, Environment.DIRECTORY_DOCUMENTS),
            )

            FileColumns.MEDIA_TYPE_NONE -> listOf(
                File(sdDir, Environment.DIRECTORY_DOWNLOADS),
            )

            else -> {
                Log.w("MountWizard", "unexpected mediaType: $mediaType")
                listOf(File(sdDir, Environment.DIRECTORY_DOWNLOADS))
            }
        }.toMutableList()
        val downloadDir = File(sdDir, Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir !in recommendDirs) {
            // Any type saved to Download dir is allowed.
            recommendDirs += downloadDir
        }
        // 真实存在、但与上面这些常量**拼写不同**的同名目录（典型：ColorOS 相册的 `pictures`）。
        recommendDirs += caseVariantOf(recommendDirs)
        return recommendDirs
    }

    /**
     * 共享存储根下真实存在、且与 [base] 中某一项「仅大小写不同」的目录。
     *
     * ## 为什么有它（防御性改进，不是任何已观测缺陷的修复项）
     *
     * 上面那张推荐表全部由 `Environment.DIRECTORY_*` 常量拼出来，而这些常量只有一种拼法
     * （`Pictures`）。厂商有时会自己建一份大小写变体并在里面放真东西，而大小写在文件系统上
     * 是**两个不同目录** —— 「保留 `Pictures`」并不会保留 `pictures`。用户做根目录重定向
     * （沙盒）时，向导里看不到那份变体，也就无从勾选，它整棵子树会掉进空沙盒。
     * 本函数把这种真实存在、但拼写不同的目录补进推荐表，属于**事前兜底**。
     *
     * ⚠️ **归因订正（2026-11-01）**：本条曾以「10-31 相册能看不能编辑」为据，写成
     * 「小写 `pictures` 未被保留导致 ENOENT，是那次故障的根因」。**该结论已被证伪**：
     * 用户始终没有改过任何相册规则，仅重启手机即恢复；11-01 重启后的规则快照与 10-31
     * **逐字节相同**（`redirectRevision=f4a54277`、size=6230，4 条规则、只有大写 `Pictures`），
     * 期间 `fuseRedirect.redirected` 却从 575 掉到 0。真实机制是**那次进程的 MediaStore
     * 快照里 `_data` 是小写、而磁盘扫描视图是大写**（重启后 MediaProvider 重建 `_data`
     * 即自愈），与本模块的匹配逻辑无关 —— 本例中重定向其实是**正确执行**的。
     * 保留本函数仅因其对「厂商变体命名」有兜底价值，勿再把它当那次故障的修复。
     *
     * ## 为什么只收「大小写变体」而不是扫描所有顶层目录
     *
     * 推荐表同时是 [autoAnswer] 的判据：`getRecommendDirs(type).none { startsWith(dir, path) }`
     * 决定某个文件事件要不要触发「根目录重定向」建议。若把 `alipay`、`tencent` 这类应用私有
     * 目录也塞进来，等于把它们一律判成「公共可访问」，q1 建议会大面积失灵。只收标准目录的
     * 大小写变体既覆盖了厂商改名的真实形态，又不会污染那个判定。
     *
     * 顺带排除 `Android`：它也在 `Environment.STANDARD_DIRECTORIES` 里，但绝不该被推荐成
     * 「应用保存有用数据的地方」。
     *
     * @param base 已经确定的推荐列表（标准目录 + Download 兜底）
     * @return 真实存在的大小写变体；目录不可枚举时返回空表，绝不抛
     */
    private fun caseVariantOf(base: List<File>): List<File> = runCatching {
        val actual = File(sdDir).listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { it.name }
        caseVariantDirNames(base.map { it.name }, actual).map { File(sdDir, it) }
    }.getOrDefault(emptyList())

    fun getRecommendDirOps(
        oldList: List<Pair<String, String>>, newList: List<Pair<String, String>>
    ): List<DirOp> {
        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldList[oldItemPosition]
                val newItem = newList[newItemPosition]
                return oldItem.first == newItem.first && oldItem.second == newItem.second
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldList[oldItemPosition]
                val newItem = newList[newItemPosition]
                return oldItem == newItem
            }
        }, false)
        val dirsNeedMigrate = mutableSetOf<String>()
        val dirOps = mutableListOf<DirOp>()
        result.dispatchUpdatesTo(object : UpdatingList<Pair<String, String>>(oldList, newList) {
            override fun onInserted(list: List<Pair<String, String>>) {
                dirsNeedMigrate += list.unzip().second
            }

            override fun onRemoved(list: List<Pair<String, String>>) {
                dirsNeedMigrate += list.unzip().second
            }

            override fun onChanged(
                originList: List<Pair<String, String>>, changedList: List<Pair<String, String>>
            ) {
                changedList.forEachIndexed { index, pair ->
                    if (originList[index].first == pair.first) {
                        dirsNeedMigrate += pair.second
                    } else {
                        dirOps += DirOp.create(originList[index].first, pair.first, packageName)
                    }
                }
            }
        })
        return dirsNeedMigrate.asSequence()
            .map { dir ->
                val oldMountedDir = RedirectReachabilityAnalyzer.mountedPath(oldList, dir)
                val newMountedDir = RedirectReachabilityAnalyzer.mountedPath(newList, dir)
                DirOp.create(oldMountedDir, newMountedDir, packageName)
            }
            .plus(dirOps)
            .filterNot {
                sdDir == it.from || RuntimeFileUtils.isStandardDirectory(
                    it.from.substring(sdDir.length + File.separator.length)
                )
            }
            .filterNot {
                RuntimeFileUtils.startsWith(it.from, it.to)
            }
            .distinct()
            .filter {
                // validate dir exist
                CleanerClient.service?.createFileModel(it.from)?.isDirectory ?: false
            }
            .toList()
    }

    sealed class DirOp(val from: String, val to: String, val checkByDefault: Boolean) {
        class Move(from: String, to: String, checkByDefault: Boolean) :
            DirOp(from, to, checkByDefault)

        class Copy(from: String, to: String, checkByDefault: Boolean) :
            DirOp(from, to, checkByDefault)

        override fun hashCode(): Int {
            var result = from.hashCode()
            result = 31 * result + to.hashCode()
            return result
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DirOp) return false

            if (from != other.from) return false
            if (to != other.to) return false

            return true
        }

        companion object {

            fun create(from: String, to: String, packageName: String): DirOp {
                val oldDirInternal = RuntimeFileUtils.isKnownAppDirPaths(from, packageName)
                val newDirInternal = RuntimeFileUtils.isKnownAppDirPaths(to, packageName)
                val oldDirExternal = !oldDirInternal
                val newDirExternal = !newDirInternal
                return when {
                    oldDirInternal && newDirInternal -> Move(from, to, true)
                    oldDirExternal && newDirExternal -> Move(from, to, true)
                    oldDirInternal && newDirExternal -> Move(from, to, false)
                    oldDirExternal && newDirInternal -> Move(from, to, true)
                    else -> {
                        Log.w("MountWizard", "unexpected dir state for $packageName: oldInternal=$oldDirInternal newInternal=$newDirInternal")
                        Move(from, to, true)
                    }
                }
            }
        }
    }
}

class RedirectAdapter(
    private val fragment: Fragment, private val answers: WizardAnswers
) : BaseKtListAdapter<Pair<String?, String?>, RedirectAdapter.ViewHolder>(CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        StorageRedirectCategoryMountRuleItemBinding.inflate(LayoutInflater.from(parent.context))
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val rule = getItem(position)
        binding.text1.text = rule.first
        binding.text1.isVisible = rule.first != null
        binding.add1.isVisible = rule.first == null
        binding.text2.text = rule.second
        binding.text2.isVisible = rule.second != null
        binding.add2.isVisible = rule.second == null
        binding.frame1.setOnClickListener {
            FilePickerDialog()
                .apply {
                    if (rule.first != null) {
                        setPath(rule.first!!)
                    }
                    setSelectType(SELECT_FOLDER)
                    addOnPositiveButtonClickListener { dir ->
                        val position = holder.bindingAdapterPosition
                        if (position != RecyclerView.NO_POSITION) {
                            answers.updateMountRules {
                                set(position, dir.toString() to rule.second)
                                if (position == itemCount - 1 && rule.second != null) {
                                    add(null to null)
                                }
                            }
                        }
                    }
                }
                .show(fragment.childFragmentManager, null)
        }
        binding.frame2.setOnClickListener {
            FilePickerDialog()
                .apply {
                    if (rule.second != null) {
                        setPath(rule.second!!)
                    }
                    setSelectType(SELECT_FOLDER)
                    addOnPositiveButtonClickListener { dir ->
                        val position = holder.bindingAdapterPosition
                        if (position != RecyclerView.NO_POSITION) {
                            answers.updateMountRules {
                                set(position, rule.first to dir.toString())
                                if (position == itemCount - 1 && rule.first != null) {
                                    add(null to null)
                                }
                            }
                        }
                    }
                }
                .show(fragment.childFragmentManager, null)
        }
        binding.frame1.setOnCreateContextMenuListener { menu, _, _ ->
            if (rule.first != null) {
                fragment.requireActivity().menuInflater.inflate(
                    R.menu.mount_rules_editor_items, menu
                )
                val bindingAdapterPosition = holder.bindingAdapterPosition
                menu.setHeaderTitle(bindingAdapterPosition.toString())
                menu.forEach { item ->
                    item.setOnMenuItemClickListener {
                        onContextItemSelected(item, bindingAdapterPosition, rule.first!!)
                    }
                }
            }
        }
        binding.frame2.setOnCreateContextMenuListener { menu, _, _ ->
            if (rule.second != null) {
                fragment.requireActivity().menuInflater.inflate(
                    R.menu.mount_rules_editor_items, menu
                )
                val bindingAdapterPosition = holder.bindingAdapterPosition
                menu.setHeaderTitle(bindingAdapterPosition.toString())
                menu.forEach { item ->
                    item.setOnMenuItemClickListener {
                        onContextItemSelected(item, bindingAdapterPosition, rule.second!!)
                    }
                }
            }
        }
    }

    private fun onContextItemSelected(item: MenuItem, position: Int, path: String): Boolean =
        when (item.itemId) {
            R.id.menu_open -> {
                OpenUtils.open(fragment.requireContext(), path, true)
                true
            }

            R.id.menu_copy -> {
                ClipboardUtils.put(fragment.requireContext(), path)
                Snackbar.make(
                    fragment.requireView(), fragment.getString(R.string.copied, path),
                    Snackbar.LENGTH_SHORT
                ).show()
                true
            }

            R.id.menu_delete -> {
                if (position < itemCount - 1) {
                    answers.updateMountRules {
                        removeAt(position)
                    }
                } else {
                    answers.updateMountRules {
                        set(position, null to null)
                    }
                }
                true
            }

            else -> {
                false
            }
        }

    class ViewHolder(val binding: StorageRedirectCategoryMountRuleItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    companion object {
        private val CALLBACK = object : DiffUtil.ItemCallback<Pair<String?, String?>>() {
            override fun areItemsTheSame(
                oldItem: Pair<String?, String?>, newItem: Pair<String?, String?>
            ): Boolean = oldItem.first == newItem.first && oldItem.second == newItem.second

            override fun areContentsTheSame(
                oldItem: Pair<String?, String?>, newItem: Pair<String?, String?>
            ): Boolean = oldItem == newItem
        }
    }
}

class PathsAdapter(
    private val fragment: Fragment, private val answers: WizardAnswers,
    @FilePickerDialog.Companion.SelectType private val selectType: Int
) : BaseKtListAdapter<String?, PathsAdapter.ViewHolder>(CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        StorageRedirectCategoryMountWizardQuestionsAccessibleFoldersItemBinding.inflate(
            LayoutInflater.from(parent.context)
        )
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val dir = getItem(position)
        binding.text.text = dir
        binding.text.isVisible = dir != null
        binding.add.isVisible = dir == null
        binding.frame.setOnClickListener {
            FilePickerDialog()
                .apply {
                    if (dir != null) {
                        setPath(dir)
                    }
                    setSelectType(selectType)
                    addOnPositiveButtonClickListener { dir ->
                        val position = holder.bindingAdapterPosition
                        if (position != RecyclerView.NO_POSITION) {
                            when (selectType) {
                                SELECT_FOLDER -> answers.updateAccessiblePlaces {
                                    set(position, dir.toString())
                                    if (position == itemCount - 1) {
                                        add(null)
                                    }
                                }

                                SELECT_FILE_AND_FOLDER -> answers.updateInaccessiblePlaces {
                                    set(position, dir.toString())
                                    if (position == itemCount - 1) {
                                        add(null)
                                    }
                                }
                            }
                        }
                    }
                }
                .show(fragment.childFragmentManager, null)
        }
        binding.frame.setOnCreateContextMenuListener { menu, _, _ ->
            if (dir != null) {
                fragment.requireActivity().menuInflater.inflate(
                    R.menu.mount_rules_editor_items, menu
                )
                val bindingAdapterPosition = holder.bindingAdapterPosition
                menu.setHeaderTitle(bindingAdapterPosition.toString())
                menu.forEach { item ->
                    item.setOnMenuItemClickListener {
                        onContextItemSelected(item, bindingAdapterPosition, dir)
                    }
                }
            }
        }
    }

    private fun onContextItemSelected(item: MenuItem, position: Int, path: String): Boolean =
        when (item.itemId) {
            R.id.menu_open -> {
                OpenUtils.open(fragment.requireContext(), path, true)
                true
            }

            R.id.menu_copy -> {
                ClipboardUtils.put(fragment.requireContext(), path)
                Snackbar.make(
                    fragment.requireView(), fragment.getString(R.string.copied, path),
                    Snackbar.LENGTH_SHORT
                ).show()
                true
            }

            R.id.menu_delete -> {
                when (selectType) {
                    SELECT_FOLDER -> answers.updateAccessiblePlaces {
                        removeAt(position)
                    }

                    SELECT_FILE_AND_FOLDER -> answers.updateInaccessiblePlaces {
                        removeAt(position)
                    }
                }
                true
            }

            else -> {
                false
            }
        }

    class ViewHolder(val binding: StorageRedirectCategoryMountWizardQuestionsAccessibleFoldersItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    companion object {
        private val CALLBACK = object : DiffUtil.ItemCallback<String?>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem
        }
    }
}

/**
 * 从 [actual]（共享存储根下真实存在的目录名）里挑出「与 [known] 中某项仅大小写不同」的那些。
 *
 * 纯函数，可 JVM 单测；调用点见 `MountWizard.caseVariantOf`。
 *
 * 规则三条：
 * 1. 不能已在 [known] 里（否则重复推荐同一个目录）；
 * 2. 忽略大小写后必须等于 [known] 中的某一项 —— 只收「厂商改了个大小写」的形态，
 *    不收 `alipay`/`tencent` 这类应用私有目录（它们会污染 autoAnswer 的 q1 判定）；
 * 3. 排除 `Android`：它在 `Environment.STANDARD_DIRECTORIES` 里，但绝不该被推荐成
 *    「应用保存有用数据的地方」。
 *
 * 真机依据（2026-10-31 / PLK110 / ColorOS17）：ColorOS 相册的截图与编辑工作目录都在
 * **小写** `pictures/` 下，而 `Environment.DIRECTORY_PICTURES` 是 `Pictures`。大小写
 * 在文件系统上是两个目录，向导只按常量推荐 ⇒ 用户勾不到 `pictures` ⇒ 根目录重定向后
 * 它整棵子树掉进空沙盒 ⇒ 相册「能看缩略图、点编辑提示无法加载该照片」。
 */
internal fun caseVariantDirNames(
    known: Collection<String>,
    actual: Collection<String>,
): List<String> {
    val knownSet = known.toHashSet()
    return actual.asSequence()
        .filter { it.isNotEmpty() }
        .filter { !it.equals(ANDROID_DIR_NAME, ignoreCase = true) }
        .filter { it !in knownSet }
        .filter { candidate -> knownSet.any { it.equals(candidate, ignoreCase = true) } }
        // 同一目录的多种拼写在真实文件系统里确实可以并存（它们是不同 inode），但推荐表里
        // 并排出现 `pictures` / `PICTURES` 只会让用户无从下手：按小写排序后只留第一个。
        .sortedBy { it.lowercase() }
        .distinctBy { it.lowercase() }
        .toList()
}

/** `Environment.DIRECTORY_ANDROID` 的字面量 —— 它是 API 29 才有的常量，本项目 minSdk 26。 */
private const val ANDROID_DIR_NAME: String = "Android"
