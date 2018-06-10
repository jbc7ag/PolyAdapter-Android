package com.trevjonez.polyadapter

import android.support.annotation.LayoutRes
import android.support.v4.util.SimpleArrayMap
import android.support.v7.util.DiffUtil
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

class PolyAdapter(private val itemProvider: ItemProvider) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  private val layoutIdRegistry = SimpleArrayMap<Int, BindingDelegate<*, *>>()
  private val classTypeRegistry = SimpleArrayMap<Class<*>, BindingDelegate<*, *>>()

  init {
    itemProvider.onAttach(this)
  }

  interface ItemProvider {
    fun getItemCount(): Int
    fun getItem(position: Int): Any
    fun onAttach(adapter: PolyAdapter)
  }

  interface BindingDelegate<ItemType, HolderType : RecyclerView.ViewHolder> {
    @get:LayoutRes
    val layoutId: Int
    val dataType: Class<ItemType>
    val itemCallback: DiffUtil.ItemCallback<ItemType>
    fun createViewHolder(itemView: View): HolderType
    fun bindView(holder: HolderType, item: ItemType)
  }

  interface IncrementalBindingDelegate<in ItemType, HolderType : RecyclerView.ViewHolder> {
    fun bindView(holderType: HolderType, item: ItemType, payloads: List<Any>)
  }

  interface OnViewRecycledDelegate<in HolderType : RecyclerView.ViewHolder> {
    fun onRecycle(holder: HolderType)
  }

  interface OnViewAttachedDelegate<in HolderType : RecyclerView.ViewHolder> {
    fun onAttach(holder: HolderType)
  }

  interface OnViewDetachedDelegate<in HolderType : RecyclerView.ViewHolder> {
    fun onDetach(holder: HolderType)
  }

  private fun getItem(position: Int) = itemProvider.getItem(position)

  override fun getItemCount() = itemProvider.getItemCount()

  fun addDelegate(delegate: BindingDelegate<*, *>) {
    val viewTypeOverwrite = layoutIdRegistry.put(delegate.layoutId, delegate)
    val dataTypeOverwrite = classTypeRegistry.put(delegate.dataType, delegate)

    when {
      viewTypeOverwrite != null && dataTypeOverwrite == null ->
        throw IllegalArgumentException(
            "Partial delegate overwrite.\n" +
                "Layout id: '${delegate.layoutId}' collides between '$viewTypeOverwrite' and '$delegate'.\n" +
                "You can use a resource alias to disambiguate multiple data types using the same layout.\n" +
                "`<item name=\"the_alias_name\" type=\"layout\">@layout/the_real_name</item>`"
        )

      viewTypeOverwrite == null && dataTypeOverwrite != null ->
        throw IllegalArgumentException(
            "Partial delegate overwrite.\n" +
                "Data type: '${delegate.dataType}' collides between '$dataTypeOverwrite' and '$delegate'."
        )

      viewTypeOverwrite != null && dataTypeOverwrite != null ->
        throw IllegalArgumentException(
            "Total delegate overwrite.\n" +
                "Layout id: '${delegate.layoutId}' and data type: '${delegate.dataType}' collides between '$viewTypeOverwrite' and '$delegate'."
        )
    }
  }

  override fun getItemViewType(position: Int): Int {
    val item = getItem(position)
    return requireNotNull(classTypeRegistry[item.javaClass]) {
      "Failed to get layout id for item of type ${item.javaClass.name}"
    }.layoutId
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val delegate = layoutIdRegistry[viewType]
    val inflater = LayoutInflater.from(parent.context)
    return delegate.createViewHolder(inflater.inflate(delegate.layoutId, parent, false))
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    val item = getItem(position)
    getDelegate(item.javaClass).bindView(holder, item)
  }

  override fun onBindViewHolder(
      holder: RecyclerView.ViewHolder,
      position: Int,
      payloads: List<Any>
  ) {
    val item = getItem(position)
    val delegate = getDelegate(item.javaClass)
    val incrementalDelegate = delegate.asIncremental()

    when {
      payloads.isNotEmpty() && incrementalDelegate != null -> {
        incrementalDelegate.bindView(holder, item, payloads)
      }
      else -> delegate.bindView(holder, item)
    }
  }

  override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
    getDelegate(holder.itemViewType).asViewRecycledDelegate()?.onRecycle(holder)
  }

  override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
    getDelegate(holder.itemViewType).asViewAttachedDelegate()?.onAttach(holder)
  }

  override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
    getDelegate(holder.itemViewType).asViewDetachedDelegate()?.onDetach(holder)
  }

  val itemCallback: DiffUtil.ItemCallback<Any> = PolyAdapterItemCallback()

  inner class PolyAdapterItemCallback : DiffUtil.ItemCallback<Any>() {
    override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
      return when {
        oldItem.javaClass == newItem.javaClass -> {
          getDelegate(newItem.javaClass).itemCallback.areItemsTheSame(oldItem, newItem)
        }
        else -> false
      }
    }

    override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
      return when {
        oldItem.javaClass == newItem.javaClass -> {
          getDelegate(newItem.javaClass).itemCallback.areContentsTheSame(oldItem, newItem)
        }
        else -> false
      }
    }

    override fun getChangePayload(oldItem: Any, newItem: Any): Any? {
      return getDelegate(newItem.javaClass).itemCallback.getChangePayload(oldItem, newItem)
    }
  }

  private fun getDelegate(layoutId: Int): BindingDelegate<Any, RecyclerView.ViewHolder> {
    @Suppress("UNCHECKED_CAST")
    return layoutIdRegistry[layoutId] as BindingDelegate<Any, RecyclerView.ViewHolder>
  }

  private fun getDelegate(clazz: Class<*>): BindingDelegate<Any, RecyclerView.ViewHolder> {
    @Suppress("UNCHECKED_CAST")
    return classTypeRegistry[clazz] as BindingDelegate<Any, RecyclerView.ViewHolder>
  }

  private inline fun <reified T> BindingDelegate<Any, RecyclerView.ViewHolder>.asType(): T? {
    return this as? T
  }

  private fun BindingDelegate<Any, RecyclerView.ViewHolder>.asIncremental() =
      asType<IncrementalBindingDelegate<Any, RecyclerView.ViewHolder>>()

  private fun BindingDelegate<Any, RecyclerView.ViewHolder>.asViewRecycledDelegate() =
      asType<OnViewRecycledDelegate<RecyclerView.ViewHolder>>()

  private fun BindingDelegate<Any, RecyclerView.ViewHolder>.asViewAttachedDelegate() =
      asType<OnViewAttachedDelegate<RecyclerView.ViewHolder>>()

  private fun BindingDelegate<Any, RecyclerView.ViewHolder>.asViewDetachedDelegate() =
      asType<OnViewDetachedDelegate<RecyclerView.ViewHolder>>()
}