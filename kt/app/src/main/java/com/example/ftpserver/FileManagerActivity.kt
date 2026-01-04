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

        // 当切换到本地标签时，刷新以显示粘贴按钮状态
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == 1) {
                    // 切换到本地标签，刷新
                    val fragment = supportFragmentManager.findFragmentByTag("f$position")
                    if (fragment is LocalFilesFragment) {
                        fragment.refresh()
                    }
                }
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        // 断开FTP连接
        FtpClientManager.disconnect()
        super.onBackPressed()
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
