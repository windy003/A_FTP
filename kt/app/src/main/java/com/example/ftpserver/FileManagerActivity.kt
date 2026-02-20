package com.example.ftpserver

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class FileManagerActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private val tabTitles = listOf("服务器", "本地")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_manager)

        supportActionBar?.title = "文件管理"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        viewPager.adapter = FileManagerPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        // 切换到本地标签时刷新（更新粘贴按钮状态）
        // 切换到服务器标签时只更新上传按钮状态，不重新加载文件（避免并发FTP调用）
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val fragment = supportFragmentManager.findFragmentByTag("f$position")
                when {
                    position == 1 && fragment is LocalFilesFragment -> fragment.refresh()
                    position == 0 && fragment is ServerFilesFragment -> fragment.refreshUploadButton()
                }
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            // 左上角返回按钮：断开连接并退出到主界面
            FtpClientManager.disconnect()
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 系统返回手势：返回当前 fragment 的上层目录，不退出主界面
        val currentPosition = viewPager.currentItem
        val fragment = supportFragmentManager.findFragmentByTag("f$currentPosition")
        when (fragment) {
            is ServerFilesFragment -> fragment.goBack()
            is LocalFilesFragment -> fragment.goBack()
        }
        // 不调用 super.onBackPressed()，阻止 Activity 退出
    }

    override fun onDestroy() {
        super.onDestroy()
        FtpClientManager.disconnect()
    }

    private inner class FileManagerPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ServerFilesFragment()
                1 -> LocalFilesFragment()
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }
}
