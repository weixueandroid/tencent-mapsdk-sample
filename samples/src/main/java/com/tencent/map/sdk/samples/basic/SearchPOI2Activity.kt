package com.tencent.map.sdk.samples.basic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tencent.lbssearch.TencentSearch
import com.tencent.lbssearch.`object`.param.SearchParam
import com.tencent.lbssearch.`object`.param.SuggestionParam
import com.tencent.lbssearch.`object`.result.SearchResultObject
import com.tencent.lbssearch.`object`.result.SuggestionResultObject
import com.tencent.lbssearch.httpresponse.HttpResponseListener
import com.tencent.map.sdk.samples.AbsMapActivity
import com.tencent.map.sdk.samples.R
import com.tencent.map.sdk.samples.tools.location.TencentLocationHelper
import com.tencent.map.sdk.samples.tools.location.TencentLocationHelper.LocationCallback
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.TencentMap.CancelableCallback
import com.tencent.tencentmap.mapsdk.maps.TencentMap.OnCameraChangeListener
import com.tencent.tencentmap.mapsdk.maps.model.*
import java.util.*

/**
 * 地图选点
 */
class SearchPOI2Activity : AbsMapActivity(), SearchView.OnQueryTextListener, SearchView.OnCloseListener, OnFocusChangeListener, OnCameraChangeListener {
    private var mTencentSearch: TencentSearch? = null
    private var mSearchView: SearchView? = null
    private var mMap: TencentMap? = null
    private var mRecyclerView: RecyclerView? = null
    private var mSearchPoiAdapter: SearchPoiAdapter? = null
    private var mPoiInfos: MutableList<PoiInfo>? = null
    private var mTencentLocationHelper: TencentLocationHelper? = null

    /**
     * 是否能进行下一步操作
     */
    private var mIsEnableNext = true
    private var mPoiMarker: Marker? = null

    /**
     * 是否运行使用搜索建议
     */
    private var mIsUseSug = true
    private var mMapCenterPointerMarker: Marker? = null
    private var mLatPosition: LatLng? = null
    override fun getLayoutId(): Int {
        return R.layout.activity_find_poi
    }

    override fun onCreate(savedInstanceState: Bundle?, pTencentMap: TencentMap) {
        super.onCreate(savedInstanceState, pTencentMap)
        mMap = pTencentMap
        mTencentSearch = TencentSearch(this)

        //数据界面初始化
        mRecyclerView = findViewById(R.id.layout_recycle_container)
        mRecyclerView?.layoutManager = LinearLayoutManager(this)
        mSearchPoiAdapter = SearchPoiAdapter(this)
        mPoiInfos = ArrayList()
        mSearchPoiAdapter?.submitList(mPoiInfos)
        mRecyclerView?.adapter = mSearchPoiAdapter
        mMap!!.setOnCameraChangeListener(this)

        //定位设置
        mTencentLocationHelper = TencentLocationHelper(this)
        mMap!!.setLocationSource(mTencentLocationHelper)
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                requestLocation()
            }
        } else if (checkCallingOrSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        }
    }

    override fun onRequestPermissions(): Array<String> {
        return arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (ret in grantResults) {
            if (ret == PackageManager.PERMISSION_DENIED) {
                mIsEnableNext = false
                Toast.makeText(this, "授权不成功，无法使用示例", Toast.LENGTH_LONG).show()
                return
            }
        }
        requestLocation()
    }

    /**
     * 请求定位
     */
    private fun requestLocation() {
        if (!mIsEnableNext) {
            return
        }
        mIsEnableNext = false
        mTencentLocationHelper!!.startLocation(object : LocationCallback {
            override fun onStatus(status: Boolean, source: String) {
                if (status) {
                    mMap!!.isMyLocationEnabled = true
                    //设置地图不跟随定位移动地图中心
                    mMap!!.setMyLocationStyle(MyLocationStyle()
                            .myLocationType(MyLocationStyle.LOCATION_TYPE_FOLLOW_NO_CENTER))
                }
            }

            override fun onLocation(pLastLocation: LatLng) {
                if (pLastLocation != null) {
                    mMap!!.animateCamera(CameraUpdateFactory.newLatLng(pLastLocation), object : CancelableCallback {
                        override fun onFinish() {
                            mMapCenterPointerMarker = mMap!!.addMarker(MarkerOptions(pLastLocation).icon(
                                    BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)))
                            val point = mMap!!.projection.toScreenLocation(pLastLocation)
                            mMapCenterPointerMarker?.setFixingPoint(point.x, point.y)
                            mMapCenterPointerMarker?.setFixingPointEnable(true)
                        }

                        override fun onCancel() {}
                    })
                    mIsEnableNext = true
                }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.find_poi, menu)
        mSearchView = menu.findItem(R.id.menu_find_poi_search).actionView as SearchView
        mSearchView!!.setOnQueryTextListener(this)
        mSearchView!!.setOnCloseListener(this)
        mSearchView!!.setOnQueryTextFocusChangeListener(this)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_find_poi_search).isEnabled = mIsEnableNext
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        if (TextUtils.isEmpty(query)) {
            clearList()
            return false
        }
        mIsUseSug = false
        //根据关键字，请求搜索列表
        val location = mTencentLocationHelper!!.lastLocation
        val param = SearchParam()
        param.keyword(query).boundary(SearchParam.Region(location.city))
        mTencentSearch!!.search(param, object : HttpResponseListener<SearchResultObject?> {

            override fun onFailure(pI: Int, pS: String, pThrowable: Throwable) {
                mRecyclerView!!.visibility = View.INVISIBLE
                Log.e("tencent-map-samples", pS, pThrowable)
            }

            override fun onSuccess(p0: Int, p1: SearchResultObject?) {
                if (p1 != null) {
                    Log.i("TAG", "onScuess()" + "////")
                    mRecyclerView!!.visibility = View.VISIBLE
                    updateSearchPoiList(p1.data)
                }
            }
        })
        return true
    }

    override fun onQueryTextChange(newText: String): Boolean {
        if (TextUtils.isEmpty(newText)) {
            mIsUseSug = true
            clearList()
            return false
        }
        if (!mIsUseSug) {
            return false
        }

        //搜索建议
        val location = mTencentLocationHelper!!.lastLocation
        val param = SuggestionParam()
        param.keyword(newText).region(location.city).location(mTencentLocationHelper!!.lastLocationLatLng)
        mTencentSearch!!.suggestion(param, object : HttpResponseListener<SuggestionResultObject?> {

            override fun onFailure(pI: Int, pS: String, pThrowable: Throwable) {
                mRecyclerView!!.visibility = View.INVISIBLE
                Log.e("tencent-map-samples", pS, pThrowable)
            }

            override fun onSuccess(p0: Int, p1: SuggestionResultObject?) {
                if (p1 != null && mIsUseSug) {
                    mRecyclerView!!.visibility = View.VISIBLE
                    updateSuggestionPoiList(p1.data)
                }
            }
        })
        return true
    }

    /**
     * 更新搜索POI结果
     */
    private fun updateSearchPoiList(pData: List<SearchResultObject.SearchResultData>) {
        if (pData.isNotEmpty()) {
            mPoiInfos!!.clear()
            for (data in pData) {
                val poiInfo: PoiInfo = PoiInfo()
                poiInfo.id = data.id
                poiInfo.name = data.title
                poiInfo.address = data.address
                poiInfo.latLng = data.latLng
                poiInfo.source = PoiInfo.Companion.SOURCE_SEARCH
                mPoiInfos!!.add(poiInfo)
            }
            mSearchPoiAdapter!!.notifyDataSetChanged()
        } else {
            clearList()
        }
    }

    /**
     * 更新搜索建议结果
     */
    private fun updateSuggestionPoiList(pData: List<SuggestionResultObject.SuggestionData>) {
        if (pData.isNotEmpty()) {
            mPoiInfos!!.clear()
            for (data in pData) {
                val poiInfo: PoiInfo = PoiInfo()
                poiInfo.id = data.id
                poiInfo.name = data.title
                poiInfo.latLng = data.latLng
                poiInfo.source = PoiInfo.Companion.SOURCE_SUG
                mPoiInfos!!.add(poiInfo)
            }
            mSearchPoiAdapter!!.notifyDataSetChanged()
        } else {
            clearList()
        }
    }

    /**
     * 在地图上显示POI
     */
    private fun performShowPoiInMap(pInfo: PoiInfo?) {
        if (checkMapInvalid()) {
            return
        }
        if (mPoiMarker != null) {
            mPoiMarker!!.remove()
        }
        mPoiMarker = mMap!!.addMarker(MarkerOptions(pInfo!!.latLng!!).title(pInfo.name).snippet(pInfo.address))
        mMap!!.animateCamera(CameraUpdateFactory.newLatLng(pInfo.latLng))
        mSearchView!!.clearFocus()
    }

    /**
     * 清空列表
     */
    private fun clearList(): Boolean {
        if (mPoiInfos!!.isNotEmpty()) {
            mPoiInfos!!.clear()
            mSearchPoiAdapter!!.notifyDataSetChanged()
            return true
        }
        return false
    }

    override fun onClose(): Boolean {
        return clearList()
    }

    override fun onFocusChange(v: View, hasFocus: Boolean) {
        //mRecyclerView.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
    }

    override fun onCameraChange(cameraPosition: CameraPosition) {}
    override fun onCameraChangeFinished(cameraPosition: CameraPosition) {
        mLatPosition = LatLng(cameraPosition.target.latitude, cameraPosition.target.longitude)
        //获取当前地图中心点，请求搜索接口
        val nearby = SearchParam.Nearby()
        nearby.point(mLatPosition)
        nearby.r(1000)
        nearby.autoExtend(true)
        val param = SearchParam("北京", nearby)
//        val param = SearchParam()
//        param.boundary(nearby)
        if (mTencentSearch != null) {
            mTencentSearch!!.search(param, object : HttpResponseListener<SearchResultObject?> {

                override fun onFailure(i: Int, s: String, throwable: Throwable) {
                    mRecyclerView!!.visibility = View.INVISIBLE
                }

                override fun onSuccess(p0: Int, p1: SearchResultObject?) {
                    if (p1 != null) {
                        mRecyclerView!!.visibility = View.VISIBLE
                        updateSearchPoiList(p1.data)
                    }
                }
            })
        }
    }

    private class PoiInfo {
        var source = 0
        var id: String? = null
        var name: String? = null
        var address: String? = null
        var latLng: LatLng? = null

        companion object {
            const val SOURCE_SUG = 0
            const val SOURCE_SEARCH = 1
        }
    }

    private inner class SearchPoiAdapter internal constructor(var mContext: Context) : ListAdapter<PoiInfo, SearchPoiItemViewHolder>(object : DiffUtil.ItemCallback<PoiInfo?>() {
        override fun areItemsTheSame(oldItem: PoiInfo, newItem: PoiInfo): Boolean {
            return oldItem.id == newItem.id
        }

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: PoiInfo, newItem: PoiInfo): Boolean {
            return oldItem == newItem
        }
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchPoiItemViewHolder {
            return SearchPoiItemViewHolder(this, parent, viewType)
        }

        override fun getItemViewType(position: Int): Int {
            val poiInfo = getItem(position)
            return poiInfo!!.source
        }

        override fun onBindViewHolder(holder: SearchPoiItemViewHolder, position: Int) {
            holder.bindView(getItem(position))
        }

        fun onItemClick(pItem: PoiInfo?) {
            if (pItem!!.source == PoiInfo.Companion.SOURCE_SUG) {
                mIsUseSug = false
                mSearchView!!.setQuery(pItem.name, true)
            } else if (pItem.source == PoiInfo.Companion.SOURCE_SEARCH) {
                performShowPoiInMap(pItem)
            }
        }
    }

    private class SearchPoiItemViewHolder internal constructor(private val mAdapter: SearchPoiAdapter, pParent: ViewGroup?, pViewType: Int) : RecyclerView.ViewHolder(LayoutInflater.from(mAdapter.mContext).inflate(getItemLayoutId(pViewType), pParent, false)) {
        private val mTitle: TextView = itemView.findViewById<TextView>(android.R.id.text1)
        private val mSubTitle: TextView? = itemView.findViewById<TextView>(android.R.id.text2)
        fun bindView(pItem: PoiInfo?) {
            mTitle.text = pItem!!.name
            if (mSubTitle != null) {
                mSubTitle.text = pItem.address
                mSubTitle.visibility = if (TextUtils.isEmpty(pItem.address)) View.GONE else View.VISIBLE
            }
            itemView.setOnClickListener { mAdapter.onItemClick(pItem) }
        }

        companion object {
            private fun getItemLayoutId(pViewType: Int): Int {
                if (pViewType == PoiInfo.Companion.SOURCE_SUG) {
                    return android.R.layout.simple_list_item_1
                } else if (pViewType == PoiInfo.Companion.SOURCE_SEARCH) {
                    return android.R.layout.simple_list_item_2
                }
                return android.R.layout.simple_list_item_2
            }
        }

    }
}